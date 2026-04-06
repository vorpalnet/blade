package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;

import org.vorpal.blade.framework.v2.config.SettingsMXBean;

@OpenAPIDefinition(info = @Info(title = "BLADE Configurator", version = "1", description = "Configuration Validation and Deployment APIs"))
@javax.ws.rs.Path("api/v1")
public class ValidationAPI {

	private static final Logger logger = Logger.getLogger(ValidationAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String DOMAIN_HOME = System.getProperty("DOMAIN_HOME",
			System.getenv().getOrDefault("DOMAIN_HOME", "."));
	private static final String CONFIG_BASE = DOMAIN_HOME + "/config/custom/vorpal/";
	private static final String SCHEMAS_DIR = CONFIG_BASE + "_schemas/";
	private static final String CLUSTERS_DIR = CONFIG_BASE + "_clusters/";
	private static final String SERVERS_DIR = CONFIG_BASE + "_servers/";
	private static final String SAMPLES_DIR = CONFIG_BASE + "_samples/";

	@GET
	@javax.ws.rs.Path("validate")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Validate all app configs against their schemas")
	public Response validateAll() {
		try {
			Set<String> apps = discoverApps();
			Map<String, Object> response = new LinkedHashMap<>();
			List<Map<String, Object>> results = new ArrayList<>();
			boolean allValid = true;

			for (String app : apps) {
				List<Map<String, Object>> appResults = validateApp(app);
				results.addAll(appResults);
				for (Map<String, Object> r : appResults) {
					if (Boolean.FALSE.equals(r.get("valid"))) {
						allValid = false;
					}
				}
			}

			response.put("valid", allValid);
			response.put("results", results);
			return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Validation failed", e);
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	@GET
	@javax.ws.rs.Path("validate/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Validate a specific app's configs against its schema")
	public Response validateOne(@PathParam("app") String app) {
		try {
			List<Map<String, Object>> appResults = validateApp(app);
			boolean allValid = appResults.stream().allMatch(r -> Boolean.TRUE.equals(r.get("valid")));

			Map<String, Object> response = new LinkedHashMap<>();
			response.put("valid", allValid);
			response.put("results", appResults);
			return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Validation failed for " + app, e);
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	@POST
	@javax.ws.rs.Path("deploy")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Validate all configs, propagate via JMX, and reload")
	public Response deployAll() {
		try {
			// Validate first
			Set<String> apps = discoverApps();
			List<Map<String, Object>> validationResults = new ArrayList<>();
			boolean allValid = true;

			for (String app : apps) {
				List<Map<String, Object>> appResults = validateApp(app);
				validationResults.addAll(appResults);
				for (Map<String, Object> r : appResults) {
					if (Boolean.FALSE.equals(r.get("valid"))) {
						allValid = false;
					}
				}
			}

			if (!allValid) {
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("deployed", false);
				response.put("reason", "Validation failed");
				response.put("results", validationResults);
				return Response.status(Response.Status.BAD_REQUEST)
						.entity(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
			}

			// Deploy
			List<Map<String, Object>> deployResults = new ArrayList<>();
			for (String app : apps) {
				deployResults.add(deployApp(app));
			}

			Map<String, Object> response = new LinkedHashMap<>();
			response.put("deployed", true);
			response.put("validation", validationResults);
			response.put("deploy", deployResults);
			return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Deploy failed", e);
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	@POST
	@javax.ws.rs.Path("deploy/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Validate one app's configs, propagate via JMX, and reload")
	public Response deployOne(@PathParam("app") String app) {
		try {
			// Validate first
			List<Map<String, Object>> appResults = validateApp(app);
			boolean allValid = appResults.stream().allMatch(r -> Boolean.TRUE.equals(r.get("valid")));

			if (!allValid) {
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("deployed", false);
				response.put("reason", "Validation failed");
				response.put("results", appResults);
				return Response.status(Response.Status.BAD_REQUEST)
						.entity(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
			}

			Map<String, Object> deployResult = deployApp(app);

			Map<String, Object> response = new LinkedHashMap<>();
			response.put("deployed", true);
			response.put("validation", appResults);
			response.put("deploy", deployResult);
			return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Deploy failed for " + app, e);
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	// --- Validation Logic ---

	private Set<String> discoverApps() throws IOException {
		Set<String> apps = new TreeSet<>();
		Path schemasPath = Paths.get(SCHEMAS_DIR);
		if (!Files.exists(schemasPath)) {
			return apps;
		}
		try (Stream<Path> stream = Files.list(schemasPath)) {
			stream.filter(p -> p.toString().endsWith(".jschema")).forEach(p -> {
				String name = p.getFileName().toString();
				apps.add(name.substring(0, name.lastIndexOf(".jschema")));
			});
		}
		return apps;
	}

	private List<Map<String, Object>> validateApp(String app) throws IOException {
		List<Map<String, Object>> results = new ArrayList<>();

		Path schemaPath = Paths.get(SCHEMAS_DIR + app + ".jschema");
		if (!Files.exists(schemaPath)) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("app", app);
			result.put("valid", false);
			result.put("errors", List.of("Schema not found: " + schemaPath));
			results.add(result);
			return results;
		}

		JsonNode schemaNode = mapper.readTree(schemaPath.toFile());
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);
		JsonSchema schema = factory.getSchema(schemaNode);

		// Domain config
		Path domainConfig = Paths.get(CONFIG_BASE + app + ".json");
		if (Files.exists(domainConfig)) {
			results.add(validateFile(app, "domain", domainConfig, schema));
		}

		// Cluster configs
		Path clustersDir = Paths.get(CLUSTERS_DIR);
		if (Files.exists(clustersDir)) {
			try (Stream<Path> clusters = Files.list(clustersDir)) {
				clusters.filter(Files::isDirectory).forEach(clusterDir -> {
					Path clusterConfig = clusterDir.resolve(app + ".json");
					if (Files.exists(clusterConfig)) {
						String clusterName = clusterDir.getFileName().toString();
						results.add(validateFile(app, "cluster/" + clusterName, clusterConfig, schema));
					}
				});
			}
		}

		// Server configs
		Path serversDir = Paths.get(SERVERS_DIR);
		if (Files.exists(serversDir)) {
			try (Stream<Path> servers = Files.list(serversDir)) {
				servers.filter(Files::isDirectory).forEach(serverDir -> {
					Path serverConfig = serverDir.resolve(app + ".json");
					if (Files.exists(serverConfig)) {
						String serverName = serverDir.getFileName().toString();
						results.add(validateFile(app, "server/" + serverName, serverConfig, schema));
					}
				});
			}
		}

		// If no config files found at all, report it
		if (results.isEmpty()) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("app", app);
			result.put("level", "none");
			result.put("valid", true);
			result.put("message", "No config files found (using sample/default)");
			results.add(result);
		}

		return results;
	}

	private Map<String, Object> validateFile(String app, String level, Path configPath, JsonSchema schema) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("app", app);
		result.put("level", level);
		result.put("file", configPath.toString());

		try {
			JsonNode configNode = mapper.readTree(configPath.toFile());
			Set<ValidationMessage> errors = schema.validate(configNode);

			if (errors.isEmpty()) {
				result.put("valid", true);
			} else {
				result.put("valid", false);
				List<String> errorMessages = new ArrayList<>();
				for (ValidationMessage msg : errors) {
					errorMessages.add(msg.getMessage());
				}
				result.put("errors", errorMessages);
			}
		} catch (Exception e) {
			result.put("valid", false);
			result.put("errors", List.of("Parse error: " + e.getMessage()));
		}

		return result;
	}

	// --- Publish Logic (JMX reload only) ---

	@POST
	@javax.ws.rs.Path("publish/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Push config to cluster apps via JMX and reload")
	public Response publishOne(@PathParam("app") String app) {
		try {
			Map<String, Object> result = publishApp(app);
			boolean published = Boolean.TRUE.equals(result.get("published"));
			if (!published) {
				return Response.status(Response.Status.NOT_FOUND)
						.entity(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)).build();
			}
			return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Publish failed for " + app, e);
			return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	private Map<String, Object> publishApp(String app) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("app", app);
		List<String> actions = new ArrayList<>();

		try {
			InitialContext ctx = new InitialContext();
			try {
				MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
				ObjectName pattern = new ObjectName("vorpal.blade:Name=" + app + ",Type=Configuration,*");
				Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(pattern, null);

				if (mbeans.isEmpty()) {
					result.put("published", false);
					result.put("message", "No MBeans found for " + app);
					return result;
				}

				for (ObjectInstance mbean : mbeans) {
					ObjectName name = mbean.getObjectName();
					SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);

					// Push domain config
					propagateConfig(settings, "domain", Paths.get(CONFIG_BASE + app + ".json"), actions);

					// Push cluster configs
					String clusterKey = name.getKeyProperty("Cluster");
					if (clusterKey != null) {
						Path clusterConfig = Paths.get(CLUSTERS_DIR + clusterKey + "/" + app + ".json");
						propagateConfig(settings, "cluster", clusterConfig, actions);
					}

					// Push server configs
					String locationKey = name.getKeyProperty("Location");
					if (locationKey != null) {
						Path serverConfig = Paths.get(SERVERS_DIR + locationKey + "/" + app + ".json");
						propagateConfig(settings, "server", serverConfig, actions);
					}

					// Reload
					settings.reload();
					actions.add("Reloaded " + name);
				}

				result.put("published", true);
				result.put("actions", actions);
			} finally {
				ctx.close();
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Publish failed for " + app, e);
			result.put("published", false);
			result.put("error", e.getMessage());
		}

		return result;
	}

	// --- Deploy Logic ---

	private Map<String, Object> deployApp(String app) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("app", app);
		List<String> actions = new ArrayList<>();

		try {
			InitialContext ctx = new InitialContext();
			try {
				MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
				ObjectName pattern = new ObjectName("vorpal.blade:Name=" + app + ",Type=Configuration,*");
				Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(pattern, null);

				if (mbeans.isEmpty()) {
					result.put("deployed", false);
					result.put("message", "No MBeans found for " + app);
					return result;
				}

				for (ObjectInstance mbean : mbeans) {
					ObjectName name = mbean.getObjectName();
					SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);

					// Push domain config
					propagateConfig(settings, "domain", Paths.get(CONFIG_BASE + app + ".json"), actions);

					// Push cluster configs
					String clusterKey = name.getKeyProperty("Cluster");
					if (clusterKey != null) {
						Path clusterConfig = Paths.get(CLUSTERS_DIR + clusterKey + "/" + app + ".json");
						propagateConfig(settings, "cluster", clusterConfig, actions);
					}

					// Push server configs
					String locationKey = name.getKeyProperty("Location");
					if (locationKey != null) {
						Path serverConfig = Paths.get(SERVERS_DIR + locationKey + "/" + app + ".json");
						propagateConfig(settings, "server", serverConfig, actions);
					}

					// Reload
					settings.reload();
					actions.add("Reloaded " + name);
				}

				result.put("deployed", true);
				result.put("actions", actions);
			} finally {
				ctx.close();
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Deploy failed for " + app, e);
			result.put("deployed", false);
			result.put("error", e.getMessage());
		}

		return result;
	}

	private void propagateConfig(SettingsMXBean settings, String configType, Path configPath, List<String> actions)
			throws IOException {
		if (!Files.exists(configPath)) {
			return;
		}

		long localTimestamp = Files.getLastModifiedTime(configPath).toMillis();
		long remoteTimestamp = settings.getLastModified(configType);

		if (localTimestamp != remoteTimestamp) {
			settings.openForWrite(configType);
			String content = new String(Files.readAllBytes(configPath));
			Scanner scanner = new Scanner(content);
			while (scanner.hasNextLine()) {
				settings.write(scanner.nextLine());
			}
			scanner.close();
			settings.close();
			actions.add("Pushed " + configType + " config from " + configPath);
		} else {
			actions.add("Skipped " + configType + " (shared filesystem, timestamps match)");
		}
	}
}
