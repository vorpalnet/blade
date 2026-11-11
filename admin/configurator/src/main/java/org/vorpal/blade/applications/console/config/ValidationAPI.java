package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import org.vorpal.blade.framework.v2.config.ConfigPublisher;
import org.vorpal.blade.framework.v2.config.SettingsMXBean;
import org.vorpal.blade.framework.v3.events.ConfigEvents;

@OpenAPIDefinition(info = @Info(title = "BLADE Configurator", version = "1", description = "Configuration Validation and Deployment APIs"))
@javax.ws.rs.Path("/")
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
			return Response.serverError().entity(errorJson(e.getMessage())).build();
		}
	}

	@GET
	@javax.ws.rs.Path("validate/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Validate a specific app's configs against its schema")
	public Response validateOne(@PathParam("app") String app) {
		if (!appName(app)) return badApp(app);
		try {
			List<Map<String, Object>> appResults = validateApp(app);
			boolean allValid = appResults.stream().allMatch(r -> Boolean.TRUE.equals(r.get("valid")));

			Map<String, Object> response = new LinkedHashMap<>();
			response.put("valid", allValid);
			response.put("results", appResults);
			return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Validation failed for " + app, e);
			return Response.serverError().entity(errorJson(e.getMessage())).build();
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

			// Deployed only where every server took it: a client reading the
			// top-level flag must not see success over a failed server.
			boolean ok = deployResults.stream().allMatch(r -> Boolean.TRUE.equals(r.get("deployed")));
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("deployed", ok);
			response.put("validation", validationResults);
			response.put("deploy", deployResults);
			return Response.status(ok ? Response.Status.OK : Response.Status.BAD_GATEWAY)
					.entity(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Deploy failed", e);
			return Response.serverError().entity(errorJson(e.getMessage())).build();
		}
	}

	@POST
	@javax.ws.rs.Path("deploy/{app}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Validate one app's configs, propagate via JMX, and reload")
	public Response deployOne(@PathParam("app") String app) {
		if (!appName(app)) return badApp(app);
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

			boolean ok = Boolean.TRUE.equals(deployResult.get("deployed"));
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("deployed", ok);
			response.put("validation", appResults);
			response.put("deploy", deployResult);
			return Response.status(ok ? Response.Status.OK : Response.Status.BAD_GATEWAY)
					.entity(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Deploy failed for " + app, e);
			return Response.serverError().entity(errorJson(e.getMessage())).build();
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
		JsonSchemaFactory factory = AiConfigService.factoryFor(schemaNode);
		JsonSchema schema = factory.getSchema(schemaNode);
		// A cluster or server file holds only what differs from the domain
		// file: checked without `required`, or a one-field overlay fails.
		JsonSchema overlaySchema = factory.getSchema(AiConfigService.withoutRequired(schemaNode));

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
						results.add(validateFile(app, "cluster/" + clusterName, clusterConfig, overlaySchema));
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
						results.add(validateFile(app, "server/" + serverName, serverConfig, overlaySchema));
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
		if (!appName(app)) return badApp(app);
		try {
			Map<String, Object> result = publishApp(app);
			boolean published = Boolean.TRUE.equals(result.get("published"));
			if (!published) {
				// Servers failed: a gateway's failure, not a missing resource.
				return Response.status(Response.Status.BAD_GATEWAY)
						.entity(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)).build();
			}
			return Response.ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)).build();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Publish failed for " + app, e);
			return Response.serverError().entity(errorJson(e.getMessage())).build();
		}
	}

	/// An application name: it goes into file paths and an MBean pattern, so
	/// "..", "/" or "*" (every app on every server) are refused.
	/// An error body, its message escaped (quotes in a message made it invalid JSON).
	private static String errorJson(String message) {
		return mapper.createObjectNode().put("error", String.valueOf(message)).toString();
	}

	static boolean appName(String app) {
		return app != null && app.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !app.contains("..");
	}

	private static Response badApp(String app) {
		return Response.status(Response.Status.BAD_REQUEST)
				.entity("{\"error\":\"not an application name\"}").type(MediaType.APPLICATION_JSON).build();
	}

	private Map<String, Object> publishApp(String app) {
		return pushAndReload(app, "published");
	}

	// --- Deploy Logic ---

	private Map<String, Object> deployApp(String app) {
		return pushAndReload(app, "deployed");
	}

	/// The shared body of publish and deploy — the two were identical apart
	/// from the result key. For each Configuration MBean of `app`: push the
	/// domain / cluster / server config files (skipped on shared filesystems
	/// where timestamps already match), then reload.
	/// One push per app at a time. An engine writes a pushed file through one
	/// shared writer (openForWrite, write per line, close): two pushes of one
	/// app at once interleave and truncate it. Publish and the auto-publish
	/// watcher both take this lock.
	private static final java.util.concurrent.ConcurrentHashMap<String, Object> PUSH_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

	static Object pushLock(String app) {
		return PUSH_LOCKS.computeIfAbsent(app, k -> new Object());
	}

	/// Applications saved (or restored) while auto-publish was off and not
	/// published since, by any page or this API: shown on every page, and
	/// published when auto-publish is switched on.
	static final java.util.Set<String> UNPUBLISHED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/// Where [#UNPUBLISHED] is kept across restarts: this server's own data
	/// folder (the list is the admin's, not a configuration to push).
	private static Path pendingFile() {
		String server = System.getProperty("weblogic.Name");
		return Paths.get("servers", server == null || server.isEmpty() ? "AdminServer" : server, "data", "blade-configurator",
				"unpublished.json");
	}

	/// Apps pending for an overlay (failed or deleted): only a Publish, which
	/// pushes every file of the app, clears them. Those read back at start
	/// count as such (what put them there is not kept).
	private static final java.util.Set<String> OVERLAY_HELD = java.util.concurrent.ConcurrentHashMap.newKeySet();

	static {
		loadPending();
	}

	/// Reads [#UNPUBLISHED] back from [#pendingFile] (at start; a test reads it again).
	static void loadPending() {
		try {
			Path f = pendingFile();
			if (Files.isRegularFile(f)) {
				for (com.fasterxml.jackson.databind.JsonNode app : new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.toFile())) {
					if (app.isTextual()) {
						UNPUBLISHED.add(app.asText());
						OVERLAY_HELD.add(app.asText());   // what put it there is not kept: only a Publish clears it
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "pending list not read; it starts empty", e);
		}
	}

	private static synchronized void savePending() {
		try {
			Path f = pendingFile();
			Files.createDirectories(f.getParent());
			Path temp = Files.createTempFile(f.getParent(), ".unpublished", ".tmp");
			Files.write(temp, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(new java.util.TreeSet<>(UNPUBLISHED)));
			Files.move(temp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			logger.log(Level.WARNING, "pending list not saved", e);
		}
	}
	/// Saves of each application, counted: a Publish clears an application
	/// only if no save landed after it read the files.
	private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> SAVES = new java.util.concurrent.ConcurrentHashMap<>();
	/// Told when [#UNPUBLISHED] changes (the Configurator's pages).
	static volatile java.util.function.Consumer<String> onUnpublishedChange;
	private static final Object UNPUBLISHED_LOCK = new Object();
	/// Sends the list, in order, off the caller's thread: a page with a full
	/// socket must not stall a push (the caller may hold pushLock).
	private static final java.util.concurrent.ExecutorService BROADCASTS = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "configurator-pending-broadcast");
		t.setDaemon(true);
		return t;
	});

	/// A save (or restore) of `app`'s file; `pending`: auto-publish was off.
	static void saved(String app, boolean pending) {
		saved(app, pending, false);
	}

	/// As [#saved(String, boolean)]; `overlay`: the file is a cluster or
	/// server one, which a later watcher push of the domain file does not carry.
	static void saved(String app, boolean pending, boolean overlay) {
		if (app == null) return;
		SAVES.computeIfAbsent(app, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
		if (pending && overlay) OVERLAY_HELD.add(app);
		if (pending && UNPUBLISHED.add(app)) unpublishedChanged();
	}

	/// Undeploy: the broadcast thread and the page hook would otherwise keep
	/// this classloader (and the servlet) alive, one more on every redeploy.
	static void shutdown() {
		onUnpublishedChange = null;
		BROADCASTS.shutdownNow();
	}

	private static long saves(String app) {
		return SAVES.computeIfAbsent(app, k -> new java.util.concurrent.atomic.AtomicLong()).get();
	}

	/// Saved, then told to every page: the snapshot is taken under one lock
	/// and sent by one thread, so pages get the changes in the order they happened.
	private static void unpublishedChanged() {
		synchronized (UNPUBLISHED_LOCK) {
			savePending();
			java.util.function.Consumer<String> tell = onUnpublishedChange;
			if (tell == null) return;
			String snapshot;
			try {
				snapshot = mapper.writeValueAsString(new java.util.TreeSet<>(UNPUBLISHED));
			} catch (Exception e) {
				return;
			}
			BROADCASTS.execute(() -> tell.accept(snapshot));
		}
	}

	/// The pending entry for templates: a push that failed on a server.
	static final String TEMPLATES = "_templates";

	/// Everything of `app` reached every server running it (a Publish, the
	/// watcher's push of the domain file, a template push): no longer pending.
	static void watcherPublished(String app) {
		if (app != null && UNPUBLISHED.remove(app)) unpublishedChanged();
	}

	/// A change not pushed everywhere (failed on a server, or the watcher was
	/// stopped mid-push): pending. Told every time, even if already listed: a
	/// page may have cleared it on its own Save.
	static void watcherMissed(String app) {
		watcherMissed(app, false);
	}

	/// As [#watcherMissed(String)]; `overlay`: a cluster or server file failed
	/// or was deleted, which a later push of the domain file does not repair.
	static void watcherMissed(String app, boolean overlay) {
		if (app == null) return;
		if (overlay) OVERLAY_HELD.add(app);
		UNPUBLISHED.add(app);
		unpublishedChanged();
	}


	/// The watcher pushed `app`'s domain file to every server: no longer
	/// pending, unless an overlay holds it.
	static void domainPushed(String app) {
		if (app != null && !OVERLAY_HELD.contains(app) && UNPUBLISHED.remove(app)) unpublishedChanged();
	}

	static Map<String, Object> pushAndReload(String app, String resultKey) {
		synchronized (pushLock(app)) {
			long savesBefore = saves(app);
			PUBLISH_READS.set(new java.util.HashMap<>());
			try {
				Map<String, Object> result = pushAndReloadLocked(app, resultKey);
				// Published: no longer pending, unless saved again after the
				// files were read (the engines have the earlier content).
				if (Boolean.TRUE.equals(result.get(resultKey))) {
					if (saves(app) == savesBefore) {
						OVERLAY_HELD.remove(app);   // every file of it was pushed
						if (UNPUBLISHED.remove(app)) unpublishedChanged();
					} else {
						unpublishedChanged();   // saved meanwhile: still pending, and every page is told
					}
				} else {
					// Failed: which file missed which server is not known here, so a
					// later push of the domain file alone does not clear it.
					OVERLAY_HELD.add(app);
				}
				return result;
			} finally {
				PUBLISH_READS.remove();
			}
		}
	}

	private static Map<String, Object> pushAndReloadLocked(String app, String resultKey) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("app", app);
		List<String> actions = new ArrayList<>();
		List<String> pushedTo = new ArrayList<>();
		String current = null;

		try {
			MBeanServer mbeanServer = ConfigPublisher.domainRuntimeMBeanServer();
			Map<ObjectName, SettingsMXBean> proxies = ConfigPublisher.configurationMBeans(mbeanServer, app);

			if (proxies.isEmpty()) {
				// No server runs it (not deployed, or it reads its file itself, as
				// blade-ai does): nothing to push, and the saved file is what it
				// loads when it starts. Published, not failed forever.
				result.put(resultKey, true);
				result.put("message", "No server runs " + app + "; it reads the saved file when it starts");
				return result;
			}

			// Templates first, so the reload's connectors find the new ones.
			// Every template: which ones this app uses is not known here, and a
			// server already holding a file is only asked for its checksum.
			try {
				TemplatePush.Summary templates = TemplatePush.pushAll(mbeanServer);
				if (templates.line != null) actions.add("Templates: " + templates.line);
				result.put("templates", templates.line);
				result.put("templatesFailed", templates.failed);
				// Pending or not on every Publish path (the REST routes too).
				if (templates.failed) watcherMissed(TEMPLATES);
				else watcherPublished(TEMPLATES);
			} catch (Exception e) {
				actions.add("Templates not pushed: " + e.getMessage());
				result.put("templates", "not pushed: " + e.getMessage());
				result.put("templatesFailed", true);
				watcherMissed(TEMPLATES);
			}

			// Every server, each on its own: one that fails (a full disk, a file it
			// may not write) must not leave the ones after it on the old config.
			List<String> failed = new ArrayList<>();
			for (Map.Entry<ObjectName, SettingsMXBean> entry : proxies.entrySet()) {
				ObjectName name = entry.getKey();
				SettingsMXBean settings = entry.getValue();
				current = name.getKeyProperty("Location");
				try {

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
				if (current != null) {
					pushedTo.add(current);
				}
				} catch (Exception serverFailed) {
					logger.log(Level.WARNING, "Publish of " + app + " failed on " + current, serverFailed);
					failed.add((current == null ? String.valueOf(name) : current) + " (" + serverFailed.getMessage() + ")");
				}
			}

			result.put("actions", actions);
			if (failed.isEmpty()) {
				result.put(resultKey, true);
			} else {
				result.put(resultKey, false);
				result.put("error", "failed on " + String.join(", ", failed)
						+ (pushedTo.isEmpty() ? "" : "; published on " + String.join(", ", pushedTo)));
			}
			ConfigEvents.published(app + ".json", app, "all", pushedTo, failed);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Publish failed for " + app, e);
			result.put(resultKey, false);
			result.put("error", e.getMessage());
			ConfigEvents.published(app + ".json", app, "all", pushedTo,
					java.util.Collections.singletonList((current == null) ? "unknown" : current));
		}

		return result;
	}

	/// Pushes `content` as the engine's `configType` file, then reads it back.
	/// The engine's open and write catch their own failures and only log them
	/// (a file it may not write, a full disk), so without the read-back a
	/// Publish reported success while the engine kept, or truncated, its file.
	/// The engine writes the lines without line breaks; the read-back is
	/// compared the same way. Throws when the engine does not hold the content.
	static void writeAndVerify(SettingsMXBean settings, String configType, String content) throws IOException {
		List<String> lines = lines(content);
		settings.openForWrite(configType);
		try {
			for (String line : lines) settings.write(line);
		} finally {
			settings.close();
		}
		StringBuilder back = new StringBuilder();
		settings.openForRead(configType);
		try {
			for (String line; (line = settings.read()) != null;) back.append(line);
		} finally {
			settings.close();
		}
		// A lone \r (JSON whitespace) is written, but the engine's readLine
		// breaks at it: compared without it.
		String sent = String.join("", lines).replace("\r", "");
		if (!sent.equals(back.toString().replace("\r", ""))) {
			throw new IOException("the server did not take the pushed " + configType.toLowerCase(java.util.Locale.ROOT)
					+ " file (it holds " + back.length() + " of " + sent.length() + " characters); see its log");
		}
	}

	/// A file's lines for a push: split on \n alone (a \r before it dropped).
	/// Scanner.nextLine also breaks at U+2028, U+2029 and U+0085, which JSON
	/// allows raw inside a string: those characters vanished on every engine.
	static List<String> lines(String content) {
		List<String> out = new ArrayList<>();
		for (String l : content.split("\n", -1)) out.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
		if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
		return out;
	}

	/// Each file as read once at the start of a publish: every engine gets the
	/// same content even if a save lands mid-publish (it is then published again).
	private static final ThreadLocal<Map<Path, String>> PUBLISH_READS = new ThreadLocal<>();

	private static void propagateConfig(SettingsMXBean settings, String configType, Path configPath, List<String> actions)
			throws IOException {
		if (!Files.exists(configPath)) {
			// The domain file deleted here while an engine on its own disk still
			// holds one: nothing to push in its place (an empty domain file is not
			// "no file"), so the Publish fails there and the app stays pending.
			if ("domain".equals(configType) && !Boolean.TRUE.equals(ConfigurationMonitor.explicitSharedFileSystem())
					&& settings.getLastModified(configType) > 0) {
				throw new IOException("its domain file is missing here, but the server still holds one");
			}
			// Deleted on the admin: an engine on its own disk still merges its
			// copy. Emptied there ({} merges as nothing), so it runs as here.
			if (!"domain".equals(configType) && !Boolean.TRUE.equals(ConfigurationMonitor.explicitSharedFileSystem())
					&& settings.getLastModified(configType) > 0) {
				writeAndVerify(settings, configType, "{}");
				actions.add("Cleared " + configType + " config (deleted on the admin)");
			}
			return;
		}
		// Declared shared storage: the engines read this very file. Pushing it
		// would have each one truncate and rewrite it in place (and on one
		// line), the race the auto-publish watcher avoids by the same flag.
		if (Boolean.TRUE.equals(ConfigurationMonitor.explicitSharedFileSystem())) {
			actions.add("Skipped " + configType + " (shared filesystem declared)");
			return;
		}

		long localTimestamp = Files.getLastModifiedTime(configPath).toMillis();
		long remoteTimestamp = settings.getLastModified(configType);

		if (localTimestamp != remoteTimestamp) {
			Map<Path, String> reads = PUBLISH_READS.get();
			String content = (reads != null) ? reads.get(configPath) : null;
			if (content == null) {
				content = new String(Files.readAllBytes(configPath), java.nio.charset.StandardCharsets.UTF_8);
				if (reads != null) reads.put(configPath, content);
			}
			writeAndVerify(settings, configType, content);
			actions.add("Pushed " + configType + " config from " + configPath);
		} else {
			actions.add("Skipped " + configType + " (shared filesystem, timestamps match)");
		}
	}
}
