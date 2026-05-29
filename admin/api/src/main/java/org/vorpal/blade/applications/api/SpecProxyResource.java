package org.vorpal.blade.applications.api;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// `GET /blade/api/api/v1/spec?app=<contextRoot>&format=json|yaml` — a
/// **constrained** same-origin proxy for a single OpenAPI document.
///
/// The browser can't fetch the document directly off the engine tier (different
/// origin), so the renderer points here instead and we fetch it server-side.
/// The target URL is structurally pinned: it is always
/// `<engineBaseUrl>/<sanitized-app>/resources/openapi.<ext>`. The host is fixed
/// (the configured engine base URL) and [ApiHttp#sanitizeApp] rejects anything
/// that could escape that path, so this is not a general-purpose relay.
///
/// For JSON documents the proxy also rewrites the spec's `servers` list to a
/// single absolute entry, `<engineBaseUrl>/<app>/resources`. Swagger generates
/// the document's paths from the `@Path` annotations alone (e.g. `/v1/session/{key}`),
/// so it knows nothing about the service's context-root or WebLogic's default
/// `/resources/*` JAX-RS servlet mapping. Without this rewrite the renderer would
/// resolve "try it" against the admin page's own origin and miss both segments.
@Path("/spec")
public class SpecProxyResource {

	private static final Logger log = Logger.getLogger(SpecProxyResource.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String YAML_MEDIA_TYPE = "application/yaml";

	@GET
	@Produces({ MediaType.APPLICATION_JSON, YAML_MEDIA_TYPE })
	public Response spec(@QueryParam("app") String app,
			@QueryParam("format") @DefaultValue("json") String format) {

		String base = ApiStartupListener.engineBaseUrl();
		if (base == null) {
			return text(Response.Status.SERVICE_UNAVAILABLE,
					"engineBaseUrl is not configured (config/custom/vorpal/api.json).");
		}

		String safeApp = ApiHttp.sanitizeApp(app);
		if (safeApp == null) {
			return text(Response.Status.BAD_REQUEST, "missing or invalid 'app' parameter");
		}

		String ext = ApiHttp.normalizeFormat(format);
		String url = ApiHttp.specUrl(base, safeApp, ext);
		String mediaType = "yaml".equals(ext) ? YAML_MEDIA_TYPE : MediaType.APPLICATION_JSON;

		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(ApiHttp.REQUEST_TIMEOUT)
					.header("Accept", mediaType)
					.GET()
					.build();
			HttpResponse<byte[]> resp = ApiHttp.CLIENT.send(req, BodyHandlers.ofByteArray());

			if (resp.statusCode() != 200) {
				return text(Response.Status.BAD_GATEWAY,
						"engine returned HTTP " + resp.statusCode() + " for " + safeApp);
			}

			byte[] body = resp.body();
			if (!"yaml".equals(ext)) {
				body = withServer(body, base + "/" + safeApp + "/resources", safeApp);
			}
			return Response.ok(body)
					.type(mediaType)
					.header("Cache-Control", "no-store")
					.build();
		} catch (Exception e) {
			log.log(Level.WARNING, "spec proxy failed for " + safeApp + " (" + url + ")", e);
			return text(Response.Status.BAD_GATEWAY,
					"could not reach engine tier for " + safeApp + ": " + e.getClass().getSimpleName());
		}
	}

	/// Replace the document's `servers` with the single absolute base the engine
	/// tier actually serves this app from. If the bytes don't parse as a JSON
	/// object, returns them untouched — the renderer can still display the raw
	/// document, just without a corrected "try it" target.
	private byte[] withServer(byte[] json, String serverUrl, String app) {
		try {
			JsonNode root = mapper.readTree(json);
			if (root == null || !root.isObject()) {
				return json;
			}
			ObjectNode spec = (ObjectNode) root;
			spec.putArray("servers").addObject().put("url", serverUrl);
			return mapper.writeValueAsBytes(spec);
		} catch (Exception e) {
			log.log(Level.FINE, "could not rewrite servers for " + app + "; passing spec through unchanged", e);
			return json;
		}
	}

	private static Response text(Response.Status status, String message) {
		return Response.status(status).type(MediaType.TEXT_PLAIN).entity(message).build();
	}
}
