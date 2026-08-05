package org.vorpal.blade.applications.console.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v2.config.SettingsMXBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;

/// REST face of the AI config assistant, for scripting and CLI use — the
/// browser editor uses the WebSocket action instead. Same BASIC-auth carve-out
/// as the validation API:
///
///     curl -u user:pass -X POST -H "Content-Type: text/plain" \
///          -d "add a queue named support released 2 per 10 seconds" \
///          http://host:7001/blade/configurator/api/v1/ai/generate/queue
///
/// Returns `{"config": ..., "valid": ..., "errors": [...]}`. Never writes or
/// publishes; feed the result to the editor or the validate/deploy APIs.
@javax.ws.rs.Path("ai")
public class AiAPI {

	private static final Logger logger = Logger.getLogger(AiAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String DOMAIN_HOME = System.getProperty("DOMAIN_HOME",
			System.getenv().getOrDefault("DOMAIN_HOME", "."));
	private static final String CONFIG_BASE = DOMAIN_HOME + "/config/custom/vorpal/";
	private static final String SCHEMAS_DIR = CONFIG_BASE + "_schemas/";
	private static final String SELF_APP = "blade-configurator";

	@POST
	@javax.ws.rs.Path("generate/{app}")
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Draft a configuration for {app} from a plain-language instruction (request body). Proposal only — nothing is written or published.")
	public Response generate(@PathParam("app") String app, String instruction) {
		if (app == null || !app.matches("[A-Za-z0-9._-]{1,128}")) {
			return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"invalid app name\"}").build();
		}
		if (instruction == null || instruction.isBlank()) {
			return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"empty instruction\"}").build();
		}
		try {
			Path schemaPath = Paths.get(SCHEMAS_DIR, app + ".jschema");
			if (!Files.exists(schemaPath)) {
				return Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"schema not found: " + app + "\"}")
						.build();
			}
			String schemaJson = Files.readString(schemaPath);

			Path configPath = Paths.get(CONFIG_BASE, app + ".json");
			String currentJson = Files.exists(configPath) ? Files.readString(configPath) : null;

			String result = AiConfigService.generate(loadAiSettings(), schemaJson, currentJson, instruction);
			return Response.ok(result).build();
		} catch (IllegalStateException e) {
			return Response.status(Response.Status.SERVICE_UNAVAILABLE)
					.entity("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}").build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "AI generate failed for " + app, e);
			return Response.serverError().entity("{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}")
					.build();
		}
	}

	/// The Configurator's own AI settings via its Configuration MBean (already
	/// decrypted in memory) — same read FileManagerServlet uses.
	private AiSettings loadAiSettings() {
		try {
			InitialContext ctx = new InitialContext();
			MBeanServer mbeanServer;
			try {
				mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			} finally {
				ctx.close();
			}
			ObjectName pattern = new ObjectName("vorpal.blade:Name=" + SELF_APP + ",Type=Configuration,*");
			Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(pattern, null);
			if (!mbeans.isEmpty()) {
				SettingsMXBean cfg = JMX.newMXBeanProxy(mbeanServer, mbeans.iterator().next().getObjectName(),
						SettingsMXBean.class);
				String json = cfg.getCurrentJson();
				if (json != null) {
					JsonNode ai = mapper.readTree(json).get("ai");
					if (ai != null) {
						return mapper.treeToValue(ai, AiSettings.class);
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "could not read configurator AI settings", e);
		}
		return new AiSettings();
	}
}
