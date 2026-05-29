package org.vorpal.blade.applications.api;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// `GET /blade/api/api/v1/services` — the discovery endpoint that populates the
/// explorer's pulldown.
///
/// Two stages:
///
/// 1. **JMX walk** of the AdminServer's DomainRuntime tree
///    (ServerRuntimes → ApplicationRuntimes → ComponentRuntimes → `ContextRoot`)
///    to enumerate every ACTIVE deployed webapp. Unlike the portal — which
///    only keeps `blade/*` admin apps — we keep **all** context-roots, because
///    the REST services that carry OpenAPI documents use flat context-roots
///    (`transfer`, `hold`, …), not the `blade/` prefix.
/// 2. **Probe** each candidate at `<engineBaseUrl>/<contextRoot>/resources/openapi.json`
///    (concurrently). Only the ones that answer `200` enter the list; their
///    `info.title` / `info.version` are read straight from the probed document.
///
/// The probe runs server-side (api WAR → engine tier), so it needs no CORS. The
/// returned `specUrl` points back at this app's own [SpecProxyResource] so the
/// browser loads the document same-origin.
@Path("/services")
public class ServicesResource {

	private static final Logger log = Logger.getLogger(ServicesResource.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final int STATE_ACTIVE = 2; // ApplicationRuntimeMBean.ActiveVersionState ACTIVE
	private static final String SELF_CONTEXT_ROOT = "blade/api";

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response list() {
		String base = ApiStartupListener.engineBaseUrl();
		if (base == null) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.type(MediaType.APPLICATION_JSON)
					.entity("{\"error\":\"engineBaseUrl is not configured. Set it in config/custom/vorpal/api.json (or via the Configurator).\",\"services\":[]}")
					.build();
		}

		try {
			List<String> contextRoots = walkContextRoots();
			List<Service> services = probeAll(base, contextRoots);
			services.sort((a, b) -> a.title.compareToIgnoreCase(b.title));

			ObjectNode out = mapper.createObjectNode();
			out.put("engineBaseUrl", base);
			ArrayNode arr = out.putArray("services");
			for (Service s : services) {
				ObjectNode n = arr.addObject();
				n.put("app", s.contextRoot);
				n.put("title", s.title);
				if (s.version != null) {
					n.put("version", s.version);
				}
				n.put("specUrl", "api/v1/spec?app=" + urlEncode(s.contextRoot));
				n.put("serverUrl", base + "/" + s.contextRoot + "/resources");
			}
			return Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			log.log(Level.WARNING, "service discovery failed", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.type(MediaType.APPLICATION_JSON)
					.entity("{\"error\":\"discovery failed: " + e.getClass().getSimpleName() + "\",\"services\":[]}")
					.build();
		}
	}

	/// Enumerate every ACTIVE webapp's context-root (normalized, leading slash
	/// stripped), excluding this app itself.
	private List<String> walkContextRoots() throws Exception {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName[] servers = (ObjectName[]) mbs.getAttribute(service, "ServerRuntimes");
			if (servers == null) {
				return new ArrayList<>(out);
			}
			for (ObjectName server : servers) {
				ObjectName[] apps = arrAttr(mbs, server, "ApplicationRuntimes");
				if (apps == null) {
					continue;
				}
				for (ObjectName app : apps) {
					if (!isActive(mbs, app)) {
						continue;
					}
					ObjectName[] components = arrAttr(mbs, app, "ComponentRuntimes");
					if (components == null) {
						continue;
					}
					for (ObjectName component : components) {
						String contextRoot = strAttr(mbs, component, "ContextRoot");
						if (contextRoot == null) {
							continue;
						}
						// WLS returns ContextRoot with a leading slash even though
						// weblogic.xml declares it without one. Normalize.
						String normalized = contextRoot.startsWith("/") ? contextRoot.substring(1) : contextRoot;
						if (!normalized.isEmpty() && !SELF_CONTEXT_ROOT.equals(normalized)) {
							out.add(normalized);
						}
					}
				}
			}
		}
		return new ArrayList<>(out);
	}

	/// Probe every candidate concurrently; keep the ones that serve an OpenAPI
	/// document.
	private List<Service> probeAll(String base, List<String> contextRoots) {
		List<CompletableFuture<Service>> futures = new ArrayList<>();
		for (String ctxRoot : contextRoots) {
			String url = ApiHttp.specUrl(base, ctxRoot, "json");
			HttpRequest req;
			try {
				req = HttpRequest.newBuilder(URI.create(url))
						.timeout(ApiHttp.REQUEST_TIMEOUT)
						.header("Accept", "application/json")
						.GET()
						.build();
			} catch (Exception badUrl) {
				continue; // context-root not URL-safe; skip
			}
			futures.add(ApiHttp.CLIENT.sendAsync(req, BodyHandlers.ofString())
					.handle((resp, err) -> toService(ctxRoot, resp, err)));
		}

		List<Service> services = new ArrayList<>();
		for (CompletableFuture<Service> f : futures) {
			Service s = f.join(); // handle() converts errors to null; never throws here
			if (s != null) {
				services.add(s);
			}
		}
		return services;
	}

	private Service toService(String ctxRoot, HttpResponse<String> resp, Throwable err) {
		if (err != null || resp == null || resp.statusCode() != 200) {
			return null;
		}
		String title = humanize(lastSegment(ctxRoot));
		String version = null;
		try {
			JsonNode info = mapper.readTree(resp.body()).get("info");
			if (info != null) {
				String t = textOrNull(info, "title");
				if (t != null) {
					title = t;
				}
				version = textOrNull(info, "version");
			}
		} catch (Exception parseFail) {
			// Document served but unparseable as JSON — still list it, with the
			// humanized fallback title. The proxy will hand the raw bytes to
			// the renderer regardless.
			log.log(Level.FINE, "openapi.json for " + ctxRoot + " did not parse; using fallback title", parseFail);
		}
		return new Service(ctxRoot, title, version);
	}

	private static String urlEncode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String lastSegment(String contextRoot) {
		int slash = contextRoot.lastIndexOf('/');
		return slash >= 0 ? contextRoot.substring(slash + 1) : contextRoot;
	}

	private static String humanize(String slug) {
		if (slug == null || slug.isEmpty()) {
			return slug;
		}
		StringBuilder out = new StringBuilder();
		boolean capNext = true;
		for (int i = 0; i < slug.length(); i++) {
			char c = slug.charAt(i);
			if (c == '-' || c == '_') {
				out.append(' ');
				capNext = true;
			} else if (capNext) {
				out.append(Character.toUpperCase(c));
				capNext = false;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String textOrNull(JsonNode parent, String field) {
		JsonNode n = parent.get(field);
		if (n == null || n.isNull()) {
			return null;
		}
		String s = n.asText();
		return (s == null || s.isEmpty()) ? null : s;
	}

	private static boolean isActive(MBeanServer mbs, ObjectName app) {
		try {
			Object v = mbs.getAttribute(app, "ActiveVersionState");
			return v == null || ((Number) v).intValue() == STATE_ACTIVE;
		} catch (Exception e) {
			return true;
		}
	}

	private static ObjectName[] arrAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			return (ObjectName[]) mbs.getAttribute(on, name);
		} catch (Exception e) {
			return null;
		}
	}

	private static String strAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? v.toString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static final class Service {
		final String contextRoot;
		final String title;
		final String version;

		Service(String contextRoot, String title, String version) {
			this.contextRoot = contextRoot;
			this.title = title;
			this.version = version;
		}
	}
}
