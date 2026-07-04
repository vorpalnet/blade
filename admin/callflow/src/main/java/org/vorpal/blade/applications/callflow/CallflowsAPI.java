package org.vorpal.blade.applications.callflow;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST endpoints for the Trace admin app.
///
/// Nothing is bundled in this WAR: the gallery is discovered and read LIVE over
/// federated JMX. Every BLADE app registers a Source MBean
/// (`vorpal.blade:Name=<app>,Type=Source[,Cluster=...]`, framework
/// `v3.source.SourceRegistrar`) inventorying the `.java` files packaged inside
/// its own WAR — so the source shown here is byte-identical to the code
/// deployed on the cluster, for framework, BLADE services, and customer-built
/// apps alike.
///
/// A clustered service registers once per engine node; all copies are
/// byte-identical, so reads are pinned to ONE node — `sourceServer` from the
/// viewer's settings (e.g. `engine0`, a node kept out of the traffic path), or
/// the lexicographically first server when unset. Browsing never touches the
/// busy engines.
///
/// The per-app manifest is the security boundary: `getSource` on the MBean only
/// answers for a class its own scan inventoried — no browser-supplied path ever
/// reaches a filesystem or classpath lookup.
@javax.ws.rs.Path("/")
@Tag(name = "Trace", description = "Record live call traces and read BLADE callflow source over JMX")
public class CallflowsAPI {

	private static final Logger log = Logger.getLogger(CallflowsAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String JMX_DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	private static final String SOURCE_PATTERN = "vorpal.blade:Name=*,Type=Source,*";

	/// Every app's source inventory, one JMX read per app, all from the pinned
	/// node: `{"apps":[{"app":"...","server":"...","sources":[...]}]}`.
	@GET
	@javax.ws.rs.Path("/apps")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List every deployed app's source inventory.")
	public Response apps() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(JMX_DOMAIN_RUNTIME);
			ObjectNode out = mapper.createObjectNode();
			ArrayNode arr = out.putArray("apps");
			for (Map.Entry<String, ObjectName> e : chooseSourceMBeans(mbs).entrySet()) {
				try {
					String manifest = (String) mbs.getAttribute(e.getValue(), "ManifestJson");
					JsonNode parsed = mapper.readTree(manifest);
					ObjectNode app = arr.addObject();
					app.put("app", e.getKey());
					app.put("server", location(e.getValue()));
					app.set("sources", parsed.get("sources"));
				} catch (Exception ex) {
					// one app's bad manifest must not empty the whole gallery
					log.log(Level.FINE, "manifest read failed for " + e.getKey()
							+ ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
				}
			}
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// One class's raw `.java`, fetched from the named app's Source MBean on
	/// the pinned node.
	@GET
	@javax.ws.rs.Path("/source")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(summary = "Return one class's live source by app and class name.")
	public Response source(@QueryParam("app") String app, @QueryParam("className") String className) {
		if (isBlank(app) || isBlank(className)) {
			return bad("Missing 'app' or 'className' parameter");
		}
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(JMX_DOMAIN_RUNTIME);
			ObjectName on = chooseSourceMBeans(mbs).get(app);
			if (on == null) {
				return bad("Unknown app: " + app);
			}
			String src = (String) mbs.invoke(on, "getSource",
					new Object[] { className }, new String[] { String.class.getName() });
			if (src == null) {
				return Response.status(Response.Status.NOT_FOUND)
						.type(MediaType.TEXT_PLAIN)
						.entity("No bundled source for " + className + " in " + app).build();
			}
			return Response.ok(src).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// One Source ObjectName per app name. The federated DomainRuntime server
	/// reports each engine node's registration separately, discriminated by the
	/// `Location` key it adds; all copies of a clustered app are byte-identical,
	/// so we pin every read to one node: the configured `sourceServer` when it
	/// matches, otherwise the lexicographically first Location (which in the
	/// standard naming — engine0, engine1, ... — is the sample-generation node).
	private Map<String, ObjectName> chooseSourceMBeans(MBeanServer mbs) throws Exception {
		String pinned = sourceServer();
		Map<String, ObjectName> chosen = new TreeMap<>();
		for (ObjectInstance inst : mbs.queryMBeans(new ObjectName(SOURCE_PATTERN), null)) {
			ObjectName on = inst.getObjectName();
			String name = on.getKeyProperty("Name");
			if (name == null) {
				continue;
			}
			ObjectName current = chosen.get(name);
			if (current == null || prefer(on, current, pinned)) {
				chosen.put(name, on);
			}
		}
		return chosen;
	}

	private static boolean prefer(ObjectName candidate, ObjectName current, String pinned) {
		String cand = location(candidate);
		String cur = location(current);
		if (pinned != null) {
			boolean candPinned = pinned.equals(cand);
			boolean curPinned = pinned.equals(cur);
			if (candPinned != curPinned) {
				return candPinned;
			}
		}
		return cand != null && (cur == null || cand.compareTo(cur) < 0);
	}

	private static String location(ObjectName on) {
		return on.getKeyProperty("Location");
	}

	private static String sourceServer() {
		CallflowSettings settings = CallflowStartup.settings();
		String server = settings != null ? settings.getSourceServer() : null;
		return isBlank(server) ? null : server.trim();
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	private Response bad(String message) {
		return Response.status(Response.Status.BAD_REQUEST).type(MediaType.TEXT_PLAIN).entity(message).build();
	}

	private Response error(Throwable t) {
		log.log(Level.WARNING, "callflow API failed", t);
		StringWriter sw = new StringWriter();
		try (PrintWriter pw = new PrintWriter(sw)) {
			t.printStackTrace(pw);
		}
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.type(MediaType.TEXT_PLAIN)
				.entity(t.getClass().getName() + ": " + t.getMessage() + "\n\n" + sw).build();
	}
}
