package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.CloseReason;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.cors.CorsFilter;
import org.vorpal.blade.framework.cors.SameOriginFilter;
import org.vorpal.blade.framework.io.VersionedFileStore;
import org.vorpal.blade.framework.v2.config.ConfigPublisher;
import org.vorpal.blade.framework.v2.config.SettingsMXBean;

@ServerEndpoint(value = "/websocket", configurator = FileManagerServlet.Handshake.class)
public class FileManagerServlet {

	private static final Logger logger = Logger.getLogger(FileManagerServlet.class.getName());
	private static final Set<Session> websocketSessions = new CopyOnWriteArraySet<>();
	private static final ObjectMapper objectMapper = new ObjectMapper();

	/// File I/O with versioned backups — the same `.versions/` discipline the
	/// schema-less `admin/files` editor uses. Default retention (20) matches the
	/// count this servlet kept before the logic was factored into the framework.
	private static final VersionedFileStore store = new VersionedFileStore();

	private static final String DOMAIN_HOME = System.getProperty("DOMAIN_HOME",
			System.getenv().getOrDefault("DOMAIN_HOME", "."));
	private static final String CONFIG_BASE = DOMAIN_HOME + "/config/custom/vorpal";
	private static final String SCHEMAS_DIR = CONFIG_BASE + "/_schemas";
	private static final String SAMPLES_DIR = CONFIG_BASE + "/_samples";
	private static final String TEMPLATES_DIR = CONFIG_BASE + "/_templates";

	/// The Configurator's own canonical app name — its flattened context path
	/// ("blade/configurator"), which is what SettingsManager uses for the MBean
	/// Name and the domain config file. Used for the auto-publish toggle's
	/// self-read/self-write/self-reload.
	private static final String SELF_APP = "blade-configurator";

	private static MBeanServer server;
	private static String domainName;

	// WebSocket Event Handlers
	@OnOpen
	public void onOpen(Session session) {

		// jwm - should be in init(), but it's not firing;
		if (domainName == null) {
			server = ManagementFactory.getPlatformMBeanServer();
			domainName = server.getDefaultDomain();
		}

		Access access = access(session);
		if (!access.sameOrigin) {
			close(session, "cross-site WebSocket refused");
			return;
		}
		websocketSessions.add(session);
	}

	@OnMessage
	public void onMessage(String message, Session session) {
		try {
			JsonNode jsonNode = objectMapper.readTree(message);
			String action = jsonNode.get("action").asText();

			switch (action) {
			case "load_schema":
				String schemaApp = jsonNode.get("appName").asText();
				String schemaContent = loadSchemaFromFilesystem(schemaApp);
				if (schemaContent == null || schemaContent.trim().isEmpty()) {
					sendMessageToSession(session, createMessage("error", "Schema not found: " + schemaApp));
				} else {
					sendMessageToSession(session, createMessage("schema_loaded", schemaContent));
				}
				break;

			case "load_json":
				String jsonFile = jsonNode.get("file").asText();
				String jsonContent = loadConfigFile(jsonFile);
				sendMessageToSession(session, createMessage("json_loaded", jsonContent));
				break;

			case "load_sample":
				String sampleApp = jsonNode.get("appName").asText();
				String sampleContent = loadSampleFromFilesystem(sampleApp);
				if (sampleContent == null || sampleContent.trim().isEmpty()) {
					sendMessageToSession(session, createMessage("error", "Sample not found: " + sampleApp));
				} else {
					sendMessageToSession(session, createMessage("sample_loaded", sampleContent));
				}
				break;

			case "list_templates":
				String listSchema = jsonNode.get("schemaName").asText();
				String templateList = listTemplates(listSchema);
				sendMessageToSession(session, createMessage("template_list", templateList));
				break;

			case "load_template":
				String templateSchema = jsonNode.get("schemaName").asText();
				String templateName = jsonNode.get("templateName").asText();
				String templateContent = loadTemplateFromFilesystem(templateSchema, templateName);
				if (templateContent == null || templateContent.trim().isEmpty()) {
					sendMessageToSession(session, createMessage("error", "Template not found: " + templateName));
				} else {
					sendMessageToSession(session, createMessage("template_loaded", templateContent));
				}
				break;

			case "list_text_files":
				String listedFiles = listTextFiles();
				sendMessageToSession(session, createMessage("text_file_list", listedFiles));
				break;

			case "load_text_file":
				String textFileName = jsonNode.get("fileName").asText();
				String textContent = loadTextFile(textFileName);
				if (textContent == null) {
					sendMessageToSession(session, createMessage("error", "File not found: " + textFileName));
				} else {
					com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
					payload.put("fileName", textFileName);
					payload.put("content", textContent);
					sendMessageToSession(session, createMessage("text_file_loaded",
							objectMapper.writeValueAsString(payload)));
				}
				break;

			case "save_text_file":
				requireWriter(session);
				String saveName = jsonNode.get("fileName").asText();
				String saveText = jsonNode.get("content").asText();
				saveTextFile(saveName, saveText);
				sendMessageToSession(session, createMessage("text_file_saved", saveName));
				break;

			case "save_json":
				requireWriter(session);
				String saveFile = jsonNode.get("file").asText();
				String saveContent = jsonNode.get("content").asText();
				saveConfigFile(saveFile, saveContent);
				sendMessageToSession(session, createMessage("save_success", "File saved successfully"));
				break;

			case "list_versions":
				String versionFile = jsonNode.get("file").asText();
				String versionList = listVersions(versionFile);
				sendMessageToSession(session, createMessage("version_list", versionList));
				break;

			case "restore_version":
				requireWriter(session);
				String restoreFile = jsonNode.get("file").asText();
				String versionTimestamp = jsonNode.get("timestamp").asText();
				String restoredContent = restoreVersion(restoreFile, versionTimestamp);
				sendMessageToSession(session, createMessage("version_restored", restoredContent));
				break;

			case "get_version_content":
				String previewFile = jsonNode.get("file").asText();
				String previewTimestamp = jsonNode.get("timestamp").asText();
				String previewContent = getVersionContent(previewFile, previewTimestamp);
				sendMessageToSession(session, createMessage("version_content", previewContent));
				break;

			case "list_text_versions": {
				// Same backups as JSON configs, but the path is relative to
				// _templates/, so resolve (and confine) it before listing.
				Path tvPath = resolveTextFilePath(jsonNode.get("fileName").asText());
				if (tvPath == null) {
					sendMessageToSession(session, createMessage("error", "Invalid path"));
				} else {
					sendMessageToSession(session,
							createMessage("text_version_list", listVersions(tvPath.toString())));
				}
				break;
			}

			case "restore_text_version":
				requireWriter(session); {
				String restoreName = jsonNode.get("fileName").asText();
				Path rvPath = resolveTextFilePath(restoreName);
				if (rvPath == null) {
					sendMessageToSession(session, createMessage("error", "Invalid path"));
				} else {
					String rvContent = restoreVersion(rvPath.toString(), jsonNode.get("timestamp").asText());
					sendMessageToSession(session,
							createMessage("text_version_restored", textVersionPayload(restoreName, rvContent)));
				}
				break;
			}

			case "get_text_version_content": {
				String tvcName = jsonNode.get("fileName").asText();
				Path tvcPath = resolveTextFilePath(tvcName);
				if (tvcPath == null) {
					sendMessageToSession(session, createMessage("error", "Invalid path"));
				} else {
					String tvcContent = getVersionContent(tvcPath.toString(), jsonNode.get("timestamp").asText());
					sendMessageToSession(session,
							createMessage("text_version_content", textVersionPayload(tvcName, tvcContent)));
				}
				break;
			}

			case "ping":
				sendMessageToSession(session, createMessage("pong", String.valueOf(System.currentTimeMillis())));
				break;

			case "reload":
				requireWriter(session);
				String reloadApp = jsonNode.get("appName").asText();
				reloadViaMBean(reloadApp);
				sendMessageToSession(session, createMessage("reload_success", "Configuration reloaded for " + reloadApp));
				break;

			case "get_autopublish":
				sendMessageToSession(session, createMessage("autopublish_state", String.valueOf(getAutoPublish())));
				break;

			case "set_autopublish":
				requireWriter(session);
				boolean enabled = jsonNode.get("enabled").asBoolean();
				setAutoPublish(enabled);
				sendMessageToSession(session, createMessage("autopublish_state", String.valueOf(enabled)));
				break;

			case "list_schemas":
				String schemasList = listSchemasFromFilesystem();
				sendMessageToSession(session, createMessage("schemas_list", schemasList));
				break;

			case "list_target_directories":
				String targetDirsList = listTargetDirectories();
				sendMessageToSession(session, createMessage("target_directories_list", targetDirsList));
				break;

			case "resolve_json_file":
				String schemaNameForResolve = jsonNode.get("schemaName").asText();
				String targetDir = jsonNode.get("targetDirectory").asText();
				String resolvedFile = resolveJsonFile(schemaNameForResolve, targetDir);
				sendMessageToSession(session, createMessage("json_file_resolved", resolvedFile));
				break;

			case "get_ai_status":
				sendMessageToSession(session, createMessage("ai_status", String.valueOf(loadAiSettings().isEnabled())));
				break;

			case "ai_generate": {
				String aiApp = jsonNode.get("appName").asText();
				String aiInstruction = jsonNode.get("instruction").asText();
				String aiBaseline = jsonNode.has("config") ? jsonNode.get("config").asText() : null;
				// A generate call can run for a minute; answer from a worker
				// thread so this WebSocket keeps servicing pings meanwhile.
				new Thread(() -> {
					try {
						AiSettings ai = loadAiSettings();
						String schemaJson = loadSchemaFromFilesystem(aiApp);
						if (schemaJson == null || schemaJson.trim().isEmpty()) {
							sendMessageToSessionAsync(session, createMessage("ai_error", "Schema not found: " + aiApp));
							return;
						}
						String result = AiConfigService.generate(ai, schemaJson, aiBaseline, aiInstruction);
						sendMessageToSessionAsync(session, createMessage("ai_result", result));
					} catch (Exception e) {
						logger.log(Level.WARNING, "AI generate failed for " + aiApp, e);
						sendMessageToSessionAsync(session, createMessage("ai_error", e.getMessage()));
					}
				}, "configurator-ai-generate").start();
				break;
			}

			default:
				sendMessageToSession(session, createMessage("error", "Unknown action: " + action));
			}

		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error processing WebSocket message", e);
			sendMessageToSession(session, createMessage("error", "Error processing request: " + e.getMessage()));
		}
	}

	@OnClose
	public void onClose(Session session) {
		websocketSessions.remove(session);
	}

	@OnError
	public void onError(Session session, Throwable throwable) {
		logger.log(Level.SEVERE, "WebSocket error for session " + session.getId(), throwable);
		websocketSessions.remove(session);
	}

	private MBeanServer getMBeanServer() throws NamingException {
		return ConfigPublisher.domainRuntimeMBeanServer();
	}

	private SettingsMXBean getMBeanProxy(MBeanServer mbeanServer, String appName) throws Exception {
		Map<ObjectName, SettingsMXBean> proxies = ConfigPublisher.configurationMBeans(mbeanServer, appName);
		return proxies.isEmpty() ? null : proxies.values().iterator().next();
	}

	private void reloadViaMBean(String appName) throws Exception {
		ConfigPublisher.reload(appName);
	}

	/// Read the live auto-publish state from the Configurator's own MBean.
	/// Reads the merged in-memory config (which includes the shipped default
	/// when no domain file exists yet), so the UI toggle reflects what the
	/// running server is actually doing. Defaults to true if it can't be read.
	private boolean getAutoPublish() {
		try {
			SettingsMXBean cfg = getMBeanProxy(getMBeanServer(), SELF_APP);
			if (cfg != null) {
				String json = cfg.getCurrentJson();
				if (json != null) {
					JsonNode ap = objectMapper.readTree(json).get("autoPublish");
					if (ap != null) {
						return ap.asBoolean(true);
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "could not read configurator auto-publish state", e);
		}
		return true;
	}

	/// Persist the auto-publish flag and republish so it takes effect live.
	/// Sets `autoPublish` in the domain `blade-configurator.json`, keeping its
	/// other keys (ConfiguratorSettingsManager seeds the file from the sample
	/// at startup; if it is gone, a file holding only this key is written), then reloads
	/// the Configurator's MBean — which fires ConfiguratorSettingsManager's
	/// initialize() hook and starts or stops the watcher thread.
	private void setAutoPublish(boolean enabled) throws Exception {
		Path file = Paths.get(CONFIG_BASE + "/" + SELF_APP + ".json");
		com.fasterxml.jackson.databind.node.ObjectNode node;
		if (Files.exists(file)) {
			JsonNode existing = objectMapper.readTree(Files.readAllBytes(file));
			node = existing.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) existing
					: objectMapper.createObjectNode();
		} else {
			node = objectMapper.createObjectNode();
		}
		node.put("autoPublish", enabled);
		String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
		saveConfigFile(file.toString(), json);
		reloadViaMBean(SELF_APP);
	}

	private String listSchemasFromFilesystem() {
		java.util.List<java.util.Map<String, Object>> schemaList = new java.util.ArrayList<>();

		try {
			Path schemasPath = Paths.get(SCHEMAS_DIR);
			if (Files.exists(schemasPath) && Files.isDirectory(schemasPath)) {
				try (java.util.stream.Stream<Path> stream = Files.list(schemasPath)) {
					stream.filter(p -> p.toString().endsWith(".jschema")).sorted().forEach(p -> {
						String fileName = p.getFileName().toString();
						String appName = fileName.substring(0, fileName.lastIndexOf(".jschema"));
						java.util.Map<String, Object> schemaInfo = new java.util.HashMap<>();
						schemaInfo.put("name", appName);
						schemaInfo.put("appName", appName);
						schemaList.add(schemaInfo);
					});
				}
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error listing schemas from filesystem", e);
		}

		try {
			return objectMapper.writeValueAsString(schemaList);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error serializing schema list", e);
			return "[]";
		}
	}

	private String loadSchemaFromFilesystem(String appName) throws IOException {
		Path schemaPath = Paths.get(SCHEMAS_DIR, sanitizeName(appName) + ".jschema");
		if (!Files.exists(schemaPath)) {
			return null;
		}
		return new String(Files.readAllBytes(schemaPath));
	}

	private String loadSampleFromFilesystem(String appName) throws IOException {
		Path samplePath = Paths.get(SAMPLES_DIR, sanitizeName(appName) + ".json.SAMPLE");
		if (!Files.exists(samplePath)) {
			return null;
		}
		return new String(Files.readAllBytes(samplePath));
	}

	/// List *.json starter templates under _templates/<schemaName>/. Returns
	/// a JSON array of {"name": "<filename-without-extension>"} — the
	/// configurator renders a pick-list from this and asks the server for
	/// the full contents when the user selects one.
	private String listTemplates(String schemaName) throws IOException {
		Path dir = Paths.get(TEMPLATES_DIR, sanitizeName(schemaName));
		com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
		if (!Files.exists(dir) || !Files.isDirectory(dir)) {
			return objectMapper.writeValueAsString(arr);
		}
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
				.sorted()
				.forEach(p -> {
					String fname = p.getFileName().toString();
					String name = fname.substring(0, fname.length() - ".json".length());
					com.fasterxml.jackson.databind.node.ObjectNode entry = objectMapper.createObjectNode();
					entry.put("name", name);
					arr.add(entry);
				});
		}
		return objectMapper.writeValueAsString(arr);
	}

	/// Read the raw JSON content of a named template under _templates/<schemaName>/.
	/// Null return means "not found"; caller signals an error to the client.
	private String loadTemplateFromFilesystem(String schemaName, String templateName) throws IOException {
		Path templatePath = Paths.get(TEMPLATES_DIR, sanitizeName(schemaName),
				sanitizeName(templateName) + ".json");
		if (!Files.exists(templatePath)) {
			return null;
		}
		return new String(Files.readAllBytes(templatePath));
	}

	/// Defensive: never allow "../" escape out of the templates directory.
	private static String sanitizeName(String name) {
		if (name == null) return "";
		return name.replace("/", "").replace("\\", "").replace("..", "").trim();
	}

	// ---------------------------------------------------------------------
	// Generic text-file editor for _templates/ contents — REST body templates
	// (HTTP-message format), LDAP query files, JDBC SQL, etc. The configurator
	// exposes a "Files" tab that lists everything under _templates/ (flat;
	// subdirs shown with their relative path) and loads/saves raw text.

	private static final java.util.Set<String> TEXT_FILE_EXTENSIONS = new java.util.HashSet<>(
			java.util.Arrays.asList("txt", "sql", "ldap", "json", "xml", "properties", "yml", "yaml", "conf"));

	/// Walk _templates/ recursively and return every regular file whose
	/// extension is in TEXT_FILE_EXTENSIONS as a JSON array of
	/// {"path": "relative/path"}. Subdirectories are included so the
	/// configurator can show a flat list with full relative paths.
	private String listTextFiles() throws IOException {
		Path root = Paths.get(TEMPLATES_DIR);
		com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
		if (!Files.exists(root) || !Files.isDirectory(root)) {
			return objectMapper.writeValueAsString(arr);
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile)
				.filter(p -> {
					String n = p.getFileName().toString().toLowerCase();
					int dot = n.lastIndexOf('.');
					if (dot < 0) return false;
					return TEXT_FILE_EXTENSIONS.contains(n.substring(dot + 1));
				})
				.sorted()
				.forEach(p -> {
					String rel = root.relativize(p).toString().replace('\\', '/');
					com.fasterxml.jackson.databind.node.ObjectNode entry = objectMapper.createObjectNode();
					entry.put("path", rel);
					arr.add(entry);
				});
		}
		return objectMapper.writeValueAsString(arr);
	}

	/// Read the raw text content of a file under _templates/. The file name
	/// is resolved through sanitizePath to keep the read inside the templates
	/// tree — no "../" escape.
	private String loadTextFile(String relPath) throws IOException {
		Path p = resolveTextFilePath(relPath);
		if (p == null || !Files.exists(p) || !Files.isRegularFile(p)) return null;
		return new String(Files.readAllBytes(p));
	}

	/// Write text content to a file under _templates/, taking a versioned
	/// backup first — same `.versions/` discipline as the JSON configs, so a
	/// bad template edit (REST body template, SQL, LDAP query) is recoverable.
	private void saveTextFile(String relPath, String content) throws IOException {
		Path p = resolveTextFilePath(relPath);
		if (p == null) throw new IOException("Invalid path: " + relPath);
		store.write(p, content == null ? "" : content);
	}

	/// Bundle a template file's name with restored/previewed version content so
	/// the client can drop it back into the right editor.
	private String textVersionPayload(String fileName, String content) throws IOException {
		com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
		payload.put("fileName", fileName);
		payload.put("content", content);
		return objectMapper.writeValueAsString(payload);
	}

	/// Resolve a caller-supplied relative path inside _templates/, rejecting
	/// any attempt to escape the root directory. Returns null for empty or
	/// obviously-bad input.
	private Path resolveTextFilePath(String relPath) {
		if (relPath == null || relPath.trim().isEmpty()) return null;
		if (relPath.contains("..")) return null;
		Path root = Paths.get(TEMPLATES_DIR).toAbsolutePath().normalize();
		Path candidate = root.resolve(relPath).toAbsolutePath().normalize();
		if (!candidate.startsWith(root)) return null;
		return candidate;
	}

	/// Resolves a configuration path the browser sent, absolute or relative to
	/// the config directory, and refuses anything outside that directory or not
	/// a JSON config. The browser is not trusted to name files: without this, a
	/// `save_json` could write a server start script and `load_json` could read
	/// the domain's secret key file.
	static Path configPath(String requested) throws IOException {
		if (requested == null || requested.trim().isEmpty()) {
			throw new IOException("no file named");
		}
		Path root = Paths.get(CONFIG_BASE).toAbsolutePath().normalize();
		Path candidate = Paths.get(requested.trim());
		candidate = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).toAbsolutePath().normalize();
		String name = candidate.getFileName().toString();
		if (!candidate.startsWith(root) || !(name.endsWith(".json") || name.endsWith(".json.SAMPLE"))) {
			throw new IOException("not a configuration file: " + requested);
		}
		return candidate;
	}

	private String loadConfigFile(String relativePath) throws IOException {
		Path filePath = configPath(relativePath);
		if (!Files.exists(filePath)) {
			throw new IOException("File does not exist: " + relativePath);
		}

		return new String(Files.readAllBytes(filePath));
	}

	private void saveConfigFile(String relativePath, String content) throws IOException {
		String realPath = relativePath;

		// If path points to a sample file, convert to primary location
		if (realPath.contains("/_samples/") && realPath.endsWith(".json.SAMPLE")) {
			realPath = realPath.replace("/_samples/", "/").replace(".json.SAMPLE", ".json");
		}

		Path filePath = configPath(realPath);

		// Encrypt any {CLEARTEXT} credentials before writing to disk
		try {
			com.fasterxml.jackson.databind.ObjectMapper encMapper = new com.fasterxml.jackson.databind.ObjectMapper();
			com.fasterxml.jackson.databind.JsonNode tree = encMapper.readTree(content);
			if (org.vorpal.blade.framework.v2.config.CredentialEncryption.encryptTree(tree)) {
				content = encMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
				System.out.println("Encrypted {CLEARTEXT} credentials in " + filePath.getFileName());
			}
		} catch (Exception e) {
			System.out.println("Warning: credential encryption skipped: " + e.getMessage());
		}

		// Backs up existing content into .versions/, creates parent dirs, writes.
		store.write(filePath, content);
	}

	private String listVersions(String relativePath) throws IOException {
		Path filePath = configPath(relativePath);
		java.util.List<java.util.Map<String, Object>> versionList = new java.util.ArrayList<>();

		// listVersions returns newest-first; preserve the JSON shape the
		// configurator UI reads (timestamp + size; it formats the date itself).
		for (VersionedFileStore.VersionInfo v : store.listVersions(filePath)) {
			java.util.Map<String, Object> versionInfo = new java.util.HashMap<>();
			versionInfo.put("timestamp", v.getTimestamp());
			versionInfo.put("size", v.getSizeBytes());
			versionInfo.put("date", new java.util.Date(v.getTimestamp()).toString());
			versionList.add(versionInfo);
		}

		return objectMapper.writeValueAsString(versionList);
	}

	private String restoreVersion(String relativePath, String timestampStr) throws IOException {
		return store.restore(configPath(relativePath), Long.parseLong(timestampStr));
	}

	private String getVersionContent(String relativePath, String timestampStr) throws IOException {
		return store.readVersion(configPath(relativePath), Long.parseLong(timestampStr));
	}

	private String listTargetDirectories() throws IOException {
		java.util.List<java.util.Map<String, Object>> targetList = new java.util.ArrayList<>();

		// Add domain directory
		Path domainPath = Paths.get(CONFIG_BASE);
		if (Files.exists(domainPath) && Files.isDirectory(domainPath)) {
			java.util.Map<String, Object> domainInfo = new java.util.HashMap<>();
			domainInfo.put("name", "Domain");
			domainInfo.put("path", CONFIG_BASE);
			domainInfo.put("type", "domain");
			domainInfo.put("displayName", "Domain (" + domainName + ")");
			targetList.add(domainInfo);
		}

		// Add cluster directories
		Path clustersPath = Paths.get(CONFIG_BASE + "/_clusters");
		if (Files.exists(clustersPath) && Files.isDirectory(clustersPath)) {
			try (java.util.stream.Stream<Path> stream = Files.list(clustersPath)) {
				stream.filter(Files::isDirectory).sorted(
						(p1, p2) -> p1.getFileName().toString().compareToIgnoreCase(p2.getFileName().toString()))
						.forEach(clusterPath -> {
							String clusterName = clusterPath.getFileName().toString();
							java.util.Map<String, Object> clusterInfo = new java.util.HashMap<>();
							clusterInfo.put("name", clusterName);
							clusterInfo.put("path", CONFIG_BASE + "/_clusters/" + clusterName);
							clusterInfo.put("type", "cluster");
							clusterInfo.put("displayName", "Cluster: " + clusterName);
							targetList.add(clusterInfo);
						});
			}
		}

		// Add server directories
		Path serversPath = Paths.get(CONFIG_BASE + "/_servers");
		if (Files.exists(serversPath) && Files.isDirectory(serversPath)) {
			try (java.util.stream.Stream<Path> stream = Files.list(serversPath)) {
				stream.filter(Files::isDirectory).sorted(
						(p1, p2) -> p1.getFileName().toString().compareToIgnoreCase(p2.getFileName().toString()))
						.forEach(serverPath -> {
							String serverName = serverPath.getFileName().toString();
							java.util.Map<String, Object> serverInfo = new java.util.HashMap<>();
							serverInfo.put("name", serverName);
							serverInfo.put("path", CONFIG_BASE + "/_servers/" + serverName);
							serverInfo.put("type", "server");
							serverInfo.put("displayName", "Server: " + serverName);
							targetList.add(serverInfo);
						});
			}
		}

		return objectMapper.writeValueAsString(targetList);
	}

	private String resolveJsonFile(String schemaName, String targetDirectory) throws IOException {
		String jsonFileName = sanitizeName(schemaName) + ".json";
		Path jsonPath = configPath(targetDirectory + "/" + jsonFileName);

		// Check if file exists in target directory
		if (Files.exists(jsonPath)) {
			java.util.Map<String, Object> result = new java.util.HashMap<>();
			result.put("jsonFile", targetDirectory + "/" + jsonFileName);
			result.put("jsonFileType", "primary");
			result.put("exists", true);
			return objectMapper.writeValueAsString(result);
		}

		// Only check for sample files if target is domain directory
		if (targetDirectory.equals(CONFIG_BASE)) {
			Path samplePath = Paths.get(SAMPLES_DIR + "/" + schemaName + ".json");
			if (Files.exists(samplePath)) {
				java.util.Map<String, Object> result = new java.util.HashMap<>();
				result.put("appName", schemaName);
				result.put("jsonFileType", "sample");
				result.put("exists", true);
				return objectMapper.writeValueAsString(result);
			}
		}

		// No file found
		java.util.Map<String, Object> result = new java.util.HashMap<>();
		result.put("jsonFile", null);
		result.put("jsonFileType", null);
		result.put("exists", false);
		return objectMapper.writeValueAsString(result);
	}

	private void sendMessageToSession(Session session, String message) {
		try {
			if (session.isOpen()) {
				session.getBasicRemote().sendText(message);
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Error sending message to session " + session.getId(), e);
		}
	}

	/// Send from a worker thread. The async remote is safe to call while the
	/// container thread may be writing ping replies on the basic remote.
	private void sendMessageToSessionAsync(Session session, String message) {
		if (session.isOpen()) {
			session.getAsyncRemote().sendText(message);
		}
	}

	/// The Configurator's own AI settings, read in process: the Configuration
	/// MBean's JSON masks the API key.
	private AiSettings loadAiSettings() {
		ConfiguratorSettings settings = ConfigurationMonitorStartup.currentSettings();
		return (settings != null && settings.getAi() != null) ? settings.getAi() : new AiSettings();
	}

	private void broadcastMessage(String message) {
		for (Session session : websocketSessions) {
			sendMessageToSession(session, message);
		}
	}

	private String createMessage(String type, String content) {
		try {
			return objectMapper.writeValueAsString(new Message(type, content));
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error creating JSON message", e);
			return "{\"type\":\"error\",\"content\":\"Error creating message\"}";
		}
	}

	// HTML Page Generation
	/// What a user may do on this socket, captured at the handshake: the endpoint
	/// sees the user afterwards but not their roles or the page that opened it.
	static final class Access {
		final boolean writer;
		final boolean sameOrigin;

		Access(boolean writer, boolean sameOrigin) {
			this.writer = writer;
			this.sameOrigin = sameOrigin;
		}
	}

	/// Latest handshake per user. A user's roles are the same on every socket;
	/// the origin flag is the page of their most recent handshake.
	private static final Map<String, Access> ACCESS = new ConcurrentHashMap<>();

	/// Records, at the handshake, whether the user may change configuration
	/// (Admin or Operator; Deployer and Monitor read) and whether the page
	/// opening the socket is from this site. A WebSocket carries the admin
	/// session cookie like any request, so a page on another site could
	/// otherwise open one and drive the editor with the operator's authority.
	public static class Handshake extends ServerEndpointConfig.Configurator {
		@Override
		public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request,
				HandshakeResponse response) {
			Principal user = request.getUserPrincipal();
			if (user == null) {
				return;
			}
			boolean writer = request.isUserInRole("Admin") || request.isUserInRole("Operator");
			boolean sameOrigin = SameOriginFilter.allowed("GET", "websocket", header(request, "Origin"),
					header(request, "Referer"), header(request, "Host"), header(request, "X-Forwarded-Host"),
					CorsFilter.parseOrigins(System.getProperty(CorsFilter.ALLOWED_ORIGINS_PROPERTY)));
			ACCESS.put(user.getName(), new Access(writer, sameOrigin));
		}

		private static String header(HandshakeRequest request, String name) {
			for (Map.Entry<String, java.util.List<String>> e : request.getHeaders().entrySet()) {
				if (name.equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
					return e.getValue().get(0);
				}
			}
			return null;
		}
	}

	private static Access access(Session session) {
		Principal user = session.getUserPrincipal();
		Access access = (user != null) ? ACCESS.get(user.getName()) : null;
		return (access != null) ? access : new Access(false, false);
	}

	private static void requireWriter(Session session) {
		if (!access(session).writer) {
			throw new SecurityException("changing configuration requires the Admin or Operator role");
		}
	}

	private static void close(Session session, String reason) {
		try {
			session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
		} catch (IOException e) {
			logger.log(Level.FINE, "closing refused WebSocket", e);
		}
	}

	public static class Message {
		public String type;
		public String content;
		public long timestamp;

		public Message() {
		}

		public Message(String type, String content) {
			this.type = type;
			this.content = content;
			this.timestamp = System.currentTimeMillis();
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getContent() {
			return content;
		}

		public void setContent(String content) {
			this.content = content;
		}

		public long getTimestamp() {
			return timestamp;
		}

		public void setTimestamp(long timestamp) {
			this.timestamp = timestamp;
		}
	}
}
