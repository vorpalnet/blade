package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v2.config.SettingsMXBean;

@WebServlet("/filemanager")
@ServerEndpoint("/websocket")
public class FileManagerServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private static final Logger logger = Logger.getLogger(FileManagerServlet.class.getName());
	private static final String DATA_FILE_PATH = "server_data.txt";
	private static final Set<Session> websocketSessions = new CopyOnWriteArraySet<>();
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final int MAX_VERSIONS = 20;

	private static final String DOMAIN_HOME = System.getProperty("DOMAIN_HOME",
			System.getenv().getOrDefault("DOMAIN_HOME", "."));
	private static final String CONFIG_BASE = DOMAIN_HOME + "/config/custom/vorpal";
	private static final String SCHEMAS_DIR = CONFIG_BASE + "/_schemas";
	private static final String SAMPLES_DIR = CONFIG_BASE + "/_samples";

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

		websocketSessions.add(session);
		// Send current file content to newly connected client
		try {
			String fileContent = readFromFile();
			sendMessageToSession(session, createMessage("file_content", fileContent));
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error sending initial file content", e);
		}
	}

	@OnMessage
	public void onMessage(String message, Session session) {
		try {
			JsonNode jsonNode = objectMapper.readTree(message);
			String action = jsonNode.get("action").asText();

			switch (action) {
			case "write_file":
				String data = jsonNode.get("data").asText();
				writeToFile(data);
				broadcastMessage(createMessage("file_updated", data));
				sendMessageToSession(session, createMessage("write_success", "File written successfully"));
				break;

			case "read_file":
				String fileContent = readFromFile();
				sendMessageToSession(session, createMessage("file_content", fileContent));
				break;

			case "append_file":
				String appendData = jsonNode.get("data").asText();
				appendToFile(appendData);
				String updatedContent = readFromFile();
				broadcastMessage(createMessage("file_updated", updatedContent));
				sendMessageToSession(session, createMessage("append_success", "Data appended successfully"));
				break;

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

			case "save_json":
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

			case "ping":
				sendMessageToSession(session, createMessage("pong", String.valueOf(System.currentTimeMillis())));
				break;

			case "reload":
				String reloadApp = jsonNode.get("appName").asText();
				reloadViaMBean(reloadApp);
				sendMessageToSession(session, createMessage("reload_success", "Configuration reloaded for " + reloadApp));
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

	// HTTP Servlet Methods
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {

		String action = request.getParameter("action");

		if ("download".equals(action)) {
			response.setContentType("text/plain");
			response.setHeader("Content-Disposition", "attachment; filename=\"server_data.txt\"");

			try {
				String content = readFromFile();
				response.getWriter().write(content);
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Error reading file for download", e);
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error reading file");
			}

		} else {
			response.setContentType("text/html");
			response.getWriter().write(getHtmlPage());
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {

		String action = request.getParameter("action");
		String data = request.getParameter("data");

		response.setContentType("application/json");

		try {
			switch (action) {
			case "write":
				writeToFile(data);
				broadcastMessage(createMessage("file_updated", data));
				response.getWriter().write("{\"status\":\"success\",\"message\":\"File written successfully\"}");
				break;

			case "read":
				String content = readFromFile();
				response.getWriter().write("{\"status\":\"success\",\"content\":\""
						+ content.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
				break;

			case "append":
				appendToFile(data);
				String updatedContent2 = readFromFile();
				broadcastMessage(createMessage("file_updated", updatedContent2));
				response.getWriter().write("{\"status\":\"success\",\"message\":\"Data appended successfully\"}");
				break;

			default:
				response.getWriter().write("{\"status\":\"error\",\"message\":\"Unknown action\"}");
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Error in POST request", e);
			response.getWriter().write("{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
		}
	}

	// --- JMX Helper Methods ---

	private MBeanServer getMBeanServer() throws NamingException {
		InitialContext ctx = new InitialContext();
		try {
			return (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
		} finally {
			ctx.close();
		}
	}

	private SettingsMXBean getMBeanProxy(MBeanServer mbeanServer, String appName) throws Exception {
		ObjectName pattern = new ObjectName("vorpal.blade:Name=" + appName + ",Type=Configuration,*");
		Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(pattern, null);
		if (mbeans.isEmpty()) {
			return null;
		}
		ObjectName name = mbeans.iterator().next().getObjectName();
		return JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);
	}

	private void reloadViaMBean(String appName) throws Exception {
		MBeanServer mbeanServer = getMBeanServer();
		ObjectName pattern = new ObjectName("vorpal.blade:Name=" + appName + ",Type=Configuration,*");
		Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(pattern, null);
		if (mbeans.isEmpty()) {
			throw new IOException("No MBean found for application: " + appName);
		}
		for (ObjectInstance mbean : mbeans) {
			SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, mbean.getObjectName(), SettingsMXBean.class);
			settings.reload();
		}
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
		Path schemaPath = Paths.get(SCHEMAS_DIR + "/" + appName + ".jschema");
		if (!Files.exists(schemaPath)) {
			return null;
		}
		return new String(Files.readAllBytes(schemaPath));
	}

	private String loadSampleFromFilesystem(String appName) throws IOException {
		Path samplePath = Paths.get(SAMPLES_DIR + "/" + appName + ".json.SAMPLE");
		if (!Files.exists(samplePath)) {
			return null;
		}
		return new String(Files.readAllBytes(samplePath));
	}

	// File Operation Methods
	private synchronized String readFromFile() throws IOException {
		Path filePath = Paths.get(DATA_FILE_PATH);
		if (!Files.exists(filePath)) {
			Files.createFile(filePath);
			return "";
		}
		return new String(Files.readAllBytes(filePath));
	}

	private String loadConfigFile(String relativePath) throws IOException {
		String realPath = relativePath;

		Path filePath = Paths.get(realPath);
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

		Path filePath = Paths.get(realPath);

		// Create version backup before saving (if file exists)
		if (Files.exists(filePath)) {
			createVersionBackup(filePath);
		}

		// Create parent directories if they don't exist
		if (filePath.getParent() != null && !Files.exists(filePath.getParent())) {
			Files.createDirectories(filePath.getParent());
		}

		Files.write(filePath, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private void createVersionBackup(Path filePath) throws IOException {
		Path versionsDir = filePath.getParent().resolve(".versions");
		if (!Files.exists(versionsDir)) {
			Files.createDirectories(versionsDir);
		}

		String fileName = filePath.getFileName().toString();
		long timestamp = System.currentTimeMillis();
		String versionFileName = fileName + "." + timestamp + ".version";
		Path versionPath = versionsDir.resolve(versionFileName);

		Files.copy(filePath, versionPath, StandardCopyOption.REPLACE_EXISTING);

		cleanupOldVersions(versionsDir, fileName);
	}

	private void cleanupOldVersions(Path versionsDir, String baseFileName) throws IOException {
		String versionPrefix = baseFileName + ".";
		java.util.List<Path> versions = new java.util.ArrayList<>();

		try (java.util.stream.Stream<Path> stream = Files.list(versionsDir)) {
			stream.filter(p -> p.getFileName().toString().startsWith(versionPrefix)).forEach(versions::add);
		}

		versions.sort((p1, p2) -> {
			String name1 = p1.getFileName().toString();
			String name2 = p2.getFileName().toString();
			return name2.compareTo(name1);
		});

		if (versions.size() > MAX_VERSIONS) {
			for (int i = MAX_VERSIONS; i < versions.size(); i++) {
				Files.delete(versions.get(i));
			}
		}
	}

	private String listVersions(String relativePath) throws IOException {
		String realPath = relativePath;
		Path filePath = Paths.get(realPath);
		String fileName = filePath.getFileName().toString();
		Path versionsDir = filePath.getParent().resolve(".versions");

		if (!Files.exists(versionsDir)) {
			return "[]";
		}

		String versionPrefix = fileName + ".";
		java.util.List<java.util.Map<String, Object>> versionList = new java.util.ArrayList<>();

		try (java.util.stream.Stream<Path> stream = Files.list(versionsDir)) {
			stream.filter(p -> p.getFileName().toString().startsWith(versionPrefix)).forEach(versionPath -> {
				try {
					String versionFileName = versionPath.getFileName().toString();
					String timestampStr = versionFileName.substring(versionPrefix.length(),
							versionFileName.lastIndexOf(".version"));
					long timestamp = Long.parseLong(timestampStr);
					long size = Files.size(versionPath);

					java.util.Map<String, Object> versionInfo = new java.util.HashMap<>();
					versionInfo.put("timestamp", timestamp);
					versionInfo.put("size", size);
					versionInfo.put("date", new java.util.Date(timestamp).toString());

					versionList.add(versionInfo);
				} catch (Exception e) {
					logger.log(Level.WARNING, "Error reading version file: " + versionPath, e);
				}
			});
		}

		versionList.sort((v1, v2) -> Long.compare((Long) v2.get("timestamp"), (Long) v1.get("timestamp")));

		return objectMapper.writeValueAsString(versionList);
	}

	private String restoreVersion(String relativePath, String timestampStr) throws IOException {
		String realPath = relativePath;
		Path filePath = Paths.get(realPath);
		String fileName = filePath.getFileName().toString();
		Path versionsDir = filePath.getParent().resolve(".versions");

		String versionFileName = fileName + "." + timestampStr + ".version";
		Path versionPath = versionsDir.resolve(versionFileName);

		if (!Files.exists(versionPath)) {
			throw new IOException("Version not found: " + versionFileName);
		}

		String content = new String(Files.readAllBytes(versionPath));

		if (Files.exists(filePath)) {
			createVersionBackup(filePath);
		}

		Files.write(filePath, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

		return content;
	}

	private String getVersionContent(String relativePath, String timestampStr) throws IOException {
		String realPath = relativePath;
		Path filePath = Paths.get(realPath);
		String fileName = filePath.getFileName().toString();
		Path versionsDir = filePath.getParent().resolve(".versions");

		String versionFileName = fileName + "." + timestampStr + ".version";
		Path versionPath = versionsDir.resolve(versionFileName);

		if (!Files.exists(versionPath)) {
			throw new IOException("Version not found: " + versionFileName);
		}

		return new String(Files.readAllBytes(versionPath));
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
		String jsonFileName = schemaName + ".json";
		Path jsonPath = Paths.get(targetDirectory + "/" + jsonFileName);

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

	private synchronized void writeToFile(String content) throws IOException {
		Path filePath = Paths.get(DATA_FILE_PATH);
		Files.write(filePath, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private synchronized void appendToFile(String content) throws IOException {
		Path filePath = Paths.get(DATA_FILE_PATH);
		if (!Files.exists(filePath)) {
			Files.createFile(filePath);
		}
		Files.write(filePath, (content + System.lineSeparator()).getBytes(), StandardOpenOption.APPEND);
	}

	// WebSocket Communication Methods
	private void sendMessageToSession(Session session, String message) {
		try {
			if (session.isOpen()) {
				session.getBasicRemote().sendText(message);
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Error sending message to session " + session.getId(), e);
		}
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
	private String getHtmlPage() {
		return "<!DOCTYPE html>\n" + "<html lang=\"en\">\n" + "<head>\n" + "    <meta charset=\"UTF-8\">\n"
				+ "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
				+ "    <title>File Manager with WebSocket</title>\n" + "    <style>\n"
				+ "        body { font-family: Arial, sans-serif; margin: 20px; background-color: #f5f5f5; }\n"
				+ "        .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n"
				+ "        .status { padding: 10px; margin: 10px 0; border-radius: 4px; }\n"
				+ "        .success { background-color: #d4edda; color: #155724; border: 1px solid #c3e6cb; }\n"
				+ "        .error { background-color: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }\n"
				+ "        .info { background-color: #d1ecf1; color: #0c5460; border: 1px solid #bee5eb; }\n"
				+ "        textarea { width: 100%; height: 200px; margin: 10px 0; padding: 10px; border: 1px solid #ddd; border-radius: 4px; }\n"
				+ "        button { background-color: #007bff; color: white; border: none; padding: 10px 20px; margin: 5px; border-radius: 4px; cursor: pointer; }\n"
				+ "        button:hover { background-color: #0056b3; }\n"
				+ "        .connection-status { font-weight: bold; }\n" + "        .connected { color: green; }\n"
				+ "        .disconnected { color: red; }\n" + "    </style>\n" + "</head>\n" + "<body>\n"
				+ "    <div class=\"container\">\n" + "        <h1>File Manager with WebSocket</h1>\n" + "        \n"
				+ "        <div class=\"status info\">\n"
				+ "            WebSocket Status: <span id=\"connectionStatus\" class=\"connection-status disconnected\">Disconnected</span>\n"
				+ "        </div>\n" + "        \n" + "        <div id=\"statusMessages\"></div>\n" + "        \n"
				+ "        <h3>File Content</h3>\n"
				+ "        <textarea id=\"fileContent\" placeholder=\"File content will appear here...\"></textarea>\n"
				+ "        \n" + "        <div>\n" + "            <button onclick=\"readFile()\">Read File</button>\n"
				+ "            <button onclick=\"writeFile()\">Write File</button>\n"
				+ "            <button onclick=\"appendToFile()\">Append to File</button>\n"
				+ "            <button onclick=\"downloadFile()\">Download File</button>\n" + "        </div>\n"
				+ "        \n" + "        <h3>Append New Data</h3>\n"
				+ "        <textarea id=\"newData\" placeholder=\"Enter data to append...\"></textarea>\n"
				+ "        <button onclick=\"appendNewData()\">Append New Data</button>\n" + "    </div>\n" + "\n"
				+ "    <script>\n" + "        let websocket;\n"
				+ "        const statusElement = document.getElementById('connectionStatus');\n"
				+ "        const messagesElement = document.getElementById('statusMessages');\n"
				+ "        const fileContentElement = document.getElementById('fileContent');\n" + "        \n"
				+ "        function connectWebSocket() {\n"
				+ "            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';\n"
				+ "            const wsUrl = protocol + '//' + window.location.host + '/websocket';\n"
				+ "            \n" + "            websocket = new WebSocket(wsUrl);\n" + "            \n"
				+ "            websocket.onopen = function() {\n"
				+ "                statusElement.textContent = 'Connected';\n"
				+ "                statusElement.className = 'connection-status connected';\n"
				+ "                showMessage('WebSocket connected successfully', 'success');\n" + "            };\n"
				+ "            \n" + "            websocket.onmessage = function(event) {\n"
				+ "                const message = JSON.parse(event.data);\n"
				+ "                handleWebSocketMessage(message);\n" + "            };\n" + "            \n"
				+ "            websocket.onclose = function() {\n"
				+ "                statusElement.textContent = 'Disconnected';\n"
				+ "                statusElement.className = 'connection-status disconnected';\n"
				+ "                showMessage('WebSocket connection closed', 'error');\n" + "                \n"
				+ "                // Attempt to reconnect after 3 seconds\n"
				+ "                setTimeout(connectWebSocket, 3000);\n" + "            };\n" + "            \n"
				+ "            websocket.onerror = function(error) {\n"
				+ "                showMessage('WebSocket error: ' + error, 'error');\n" + "            };\n"
				+ "        }\n" + "        \n" + "        function handleWebSocketMessage(message) {\n"
				+ "            switch(message.type) {\n" + "                case 'file_content':\n"
				+ "                    fileContentElement.value = message.content;\n" + "                    break;\n"
				+ "                case 'file_updated':\n"
				+ "                    fileContentElement.value = message.content;\n"
				+ "                    showMessage('File updated by another client', 'info');\n"
				+ "                    break;\n" + "                case 'write_success':\n"
				+ "                case 'append_success':\n"
				+ "                    showMessage(message.content, 'success');\n" + "                    break;\n"
				+ "                case 'error':\n" + "                    showMessage(message.content, 'error');\n"
				+ "                    break;\n" + "            }\n" + "        }\n" + "        \n"
				+ "        function sendWebSocketMessage(action, data) {\n"
				+ "            if (websocket && websocket.readyState === WebSocket.OPEN) {\n"
				+ "                websocket.send(JSON.stringify({action: action, data: data}));\n"
				+ "            } else {\n" + "                showMessage('WebSocket not connected', 'error');\n"
				+ "            }\n" + "        }\n" + "        \n" + "        function readFile() {\n"
				+ "            sendWebSocketMessage('read_file', '');\n" + "        }\n" + "        \n"
				+ "        function writeFile() {\n" + "            const content = fileContentElement.value;\n"
				+ "            sendWebSocketMessage('write_file', content);\n" + "        }\n" + "        \n"
				+ "        function appendToFile() {\n" + "            const content = fileContentElement.value;\n"
				+ "            sendWebSocketMessage('append_file', content);\n" + "        }\n" + "        \n"
				+ "        function appendNewData() {\n"
				+ "            const newData = document.getElementById('newData').value;\n"
				+ "            if (newData.trim()) {\n"
				+ "                sendWebSocketMessage('append_file', newData);\n"
				+ "                document.getElementById('newData').value = '';\n" + "            }\n" + "        }\n"
				+ "        \n" + "        function downloadFile() {\n"
				+ "            window.location.href = '/filemanager?action=download';\n" + "        }\n" + "        \n"
				+ "        function showMessage(message, type) {\n"
				+ "            const messageDiv = document.createElement('div');\n"
				+ "            messageDiv.className = 'status ' + type;\n"
				+ "            messageDiv.textContent = message;\n"
				+ "            messagesElement.appendChild(messageDiv);\n" + "            \n"
				+ "            setTimeout(() => {\n" + "                if (messageDiv.parentNode) {\n"
				+ "                    messageDiv.parentNode.removeChild(messageDiv);\n" + "                }\n"
				+ "            }, 5000);\n" + "        }\n" + "        \n"
				+ "        window.onload = function() {\n" + "            connectWebSocket();\n" + "        };\n"
				+ "    </script>\n" + "</body>\n" + "</html>";
	}

	// Message class for JSON serialization
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
