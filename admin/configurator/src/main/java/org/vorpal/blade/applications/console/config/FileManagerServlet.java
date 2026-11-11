package org.vorpal.blade.applications.console.config;

import org.vorpal.blade.framework.v3.ai.AiSettings;

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

import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;
import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;

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
import org.vorpal.blade.framework.v3.events.ConfigEvents;

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
		ValidationAPI.onUnpublishedChange = this::broadcastUnpublished;   // sessions are shared: any endpoint can tell them all

		// jwm - should be in init(), but it's not firing;
		if (domainName == null) {
			server = ManagementFactory.getPlatformMBeanServer();
			domainName = server.getDefaultDomain();
		}

		Principal user = session.getUserPrincipal();
		String hkey = (user != null) ? handshakeKey(user.getName(), session.getQueryString()) : null;
		Access handshake = (hkey != null) ? HANDSHAKES.remove(hkey) : null;
		if (hkey != null) HANDSHAKE_TIMES.remove(hkey);
		session.getUserProperties().put(ACCESS_KEY, (handshake != null) ? handshake : new Access(false, false));
		Access access = access(session);
		if (!access.sameOrigin) {
			close(session, "cross-site WebSocket refused");
			return;
		}
		websocketSessions.add(session);
	}

	@OnMessage
	public void onMessage(String message, Session session) {
		String action = null;
		String requestFile = null;   // the file a failed save was for
		String requestSeq = null;    // the page's load a failed load was for
		try {
			JsonNode jsonNode = objectMapper.readTree(message);
			action = jsonNode.get("action").asText();
			requestFile = jsonNode.path("file").asText(null);
			requestSeq = jsonNode.path("seq").asText(null);

			switch (action) {
			case "load_schema":
				String schemaApp = jsonNode.get("appName").asText();
				String schemaContent = loadSchemaFromFilesystem(schemaApp);
				if (schemaContent == null || schemaContent.trim().isEmpty()) {
					sendMessageToSession(session, loadError(requestSeq, "Schema not found: " + schemaApp));
				} else {
					sendMessageToSession(session, createMessage("schema_loaded", schemaContent));
				}
				break;

			case "load_json":
				String jsonFile = jsonNode.get("file").asText();
				// The version first, then the content: a save landing between the
				// two then shows as a conflict, never as a silent overwrite.
				String loadedVersion = versionJson(jsonFile);
				String jsonContent = loadConfigFile(jsonFile);
				sendMessageToSession(session, createMessage("json_loaded", jsonContent));
				// The version the page now edits, sent back with its Save so a
				// file someone else saved since is not silently overwritten.
				sendMessageToSession(session, createMessage("file_version", loadedVersion));
				break;

			case "load_sample":
				String sampleApp = jsonNode.get("appName").asText();
				String sampleContent = loadSampleFromFilesystem(sampleApp);
				if (sampleContent == null || sampleContent.trim().isEmpty()) {
					sendMessageToSession(session, loadError(requestSeq, "Sample not found: " + sampleApp));
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
				// The version first, then the content, as load_json: a save between
				// the two shows as a conflict, never as a silent overwrite.
				String textLoadedVersion = fileVersion(resolveTextFilePath(textFileName));
				String textContent = loadTextFile(textFileName);
				if (textContent == null) {
					sendMessageToSession(session, createMessage("error", "File not found: " + textFileName));
				} else {
					com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
					payload.put("fileName", textFileName);
					payload.put("version", textLoadedVersion);
					payload.put("content", textContent);
					sendMessageToSession(session, createMessage("text_file_loaded",
							objectMapper.writeValueAsString(payload)));
				}
				break;

			case "save_text_file":
				requireWriter(session);
				String saveName = jsonNode.get("fileName").asText();
				String saveText = jsonNode.get("content").asText();
				// The same version check as a config save: a template another
				// operator saved since is not silently overwritten.
				Path textPath = resolveTextFilePath(saveName);
				String textBase = jsonNode.path("baseVersion").asText(null);
				boolean textConflict;
				String textAfter;
				synchronized (SAVE_LOCKS.computeIfAbsent(String.valueOf(textPath), k -> new Object())) {
					textConflict = textPath != null && textBase != null && !jsonNode.path("force").asBoolean(false)
							&& !textBase.equals(fileVersion(textPath));
					if (!textConflict) saveTextFile(saveName, saveText);
					textAfter = fileVersion(textPath);
				}
				if (textConflict) {
					sendMessageToSession(session, createMessage("text_save_conflict", saveName));
					break;
				}
				saved(session, "_templates/" + saveName, "saved");
				com.fasterxml.jackson.databind.node.ObjectNode textSaved = objectMapper.createObjectNode();
				textSaved.put("fileName", saveName);
				textSaved.put("version", textAfter);
				// Pushed to every server now with auto-publish on, else by Publish.
				boolean pushNow = getAutoPublish();
				textSaved.put("autoPublish", pushNow);
				sendMessageToSession(session, createMessage("text_file_saved", objectMapper.writeValueAsString(textSaved)));
				if (pushNow) pushTemplate(session, saveName);
				else ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);   // pending on every page until a Publish
				break;

			case "save_json":
				requireWriter(session);
				String saveFile = jsonNode.get("file").asText();
				String saveContent = jsonNode.get("content").asText();
				// One JSON object and nothing after it, as a restore requires: "",
				// "[]", "null" or "{} {}" would be written as is, and go live.
				if (!wholeObject(saveContent)) throw new IOException("a configuration is one JSON object { … }");
				// Saved over a version the page never saw: refuse, and say so.
				// The check and the write hold the file's lock, so two operators
				// saving at once cannot both pass the check.
				String baseVersion = jsonNode.path("baseVersion").asText(null);
				// A clone names no version: it must not replace a file already
				// at the destination unless the operator chose to.
				if (jsonNode.path("clone").asBoolean(false)) baseVersion = "none";
				boolean conflict;
				java.util.List<String> refused;   // what loading it would fail on
				String afterVersion;   // read under the lock: the version this save made
				// Auto-publish as it was before the write: switched on between the
				// write and the record, the new watcher never saw this file.
				boolean autoBefore = autoPublishKnownOn();
				// The app's lock, then the file's: the id check reads the app's other
				// files, which a save of one of them must not change meanwhile.
				synchronized (appLock(saveFile)) {
					synchronized (saveLock(saveFile)) {
						conflict = baseVersion != null && !jsonNode.path("force").asBoolean(false)
								&& !baseVersion.equals(fileVersion(configPath(saveFile)));
						refused = conflict ? java.util.Collections.emptyList() : identityProblems(saveFile, saveContent);
						if (!conflict && refused.isEmpty()) saveConfigFile(saveFile, saveContent, jsonNode.path("sourceText").asText(null),
									jsonNode.path("sourceFile").asText(null), jsonNode.path("editorText").asText(null));
						afterVersion = versionJson(saveFile);
					}
				}
				if (!refused.isEmpty()) {
					com.fasterxml.jackson.databind.node.ObjectNode why = objectMapper.createObjectNode();
					why.put("file", saveFile);
					why.set("problems", objectMapper.valueToTree(refused));
					sendMessageToSession(session, createMessage("save_refused", objectMapper.writeValueAsString(why)));
					break;
				}
				if (conflict) {
					sendMessageToSession(session, createMessage("save_conflict", afterVersion));
					break;
				}
				saved(session, saveFile, "saved");
				// Saved with auto-publish off: not live until published, which
				// any page, now or later, must be able to see and do.
				ValidationAPI.saved(appOfConfig(saveFile), !autoBefore || !autoPublishKnownOn(), isOverlay(saveFile));
				// Names the file written: a clone writes another target's file,
				// and the page must not take that for a save of its own.
				sendMessageToSession(session, createMessage("save_success", afterVersion));
				// The Configurator's own settings, at any level: their merged
				// auto-publish state put into effect, and every page told it.
				if (SELF_APP.equals(appOfConfig(saveFile))) ownSettingsWritten();
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
				String restoredContent = null;
				String restoredVersion = null;
				String notRestored = null;   // sent after the locks: a slow page must not hold them
				boolean autoBeforeRestore = autoPublishKnownOn();
				synchronized (appLock(restoreFile)) {
					synchronized (saveLock(restoreFile)) {   // a restore is a write like a save
						// Checked without a BOM (a hand-edited file kept one), as a load reads it.
						String restoreText = stripBom(getVersionContent(restoreFile, versionTimestamp));
						// A version that does not parse (a broken file later fixed in
						// the JSON tab) would go live and fail every server's reload.
						java.util.List<String> restoreRefused = java.util.Collections.emptyList();
						try {
							if (!wholeObject(restoreText)) throw new IOException("empty, not an object, or text after it");
						} catch (IOException unparseable) {
							notRestored = "Not restored: that version is not valid JSON ("
									+ String.valueOf(unparseable.getMessage()).split("\n")[0] + ")";
						}
						if (notRestored == null) {
							try {
								restoreRefused = identityProblems(restoreFile, restoreText);
							} catch (IOException uncheckable) {
								// Not the version's fault (cluster membership unreadable, say).
								notRestored = "Not restored: it could not be checked ("
										+ String.valueOf(uncheckable.getMessage()).split("\n")[0] + ")";
							}
						}
						if (notRestored == null && !restoreRefused.isEmpty()) {
							notRestored = "Not restored: " + restoreRefused.get(0)
									+ (restoreRefused.size() > 1 ? " (and " + (restoreRefused.size() - 1) + " more)" : "");
						}
						if (notRestored == null) {
							restoredContent = restoreVersion(restoreFile, versionTimestamp);
							restoredVersion = versionJson(restoreFile);
						}
					}
				}
				if (notRestored != null) {
					sendMessageToSession(session, createMessage("error", notRestored));
					break;
				}
				saved(session, restoreFile, "restored");
				ValidationAPI.saved(appOfConfig(restoreFile), !autoBeforeRestore || !autoPublishKnownOn(), isOverlay(restoreFile));   // a restore is a save
				sendMessageToSession(session, createMessage("version_restored", restoredContent));
				// The Configurator's own settings restored: as after a Save of them.
				if (SELF_APP.equals(appOfConfig(restoreFile))) ownSettingsWritten();
				// The file changed on disk: the page's next Save is over this version.
				sendMessageToSession(session, createMessage("file_version", restoredVersion));
				break;

			case "get_version_content":
				String previewFile = jsonNode.get("file").asText();
				String previewTimestamp = jsonNode.get("timestamp").asText();
				java.util.Map<String, String> preview = new java.util.LinkedHashMap<>();
				preview.put("file", previewFile);   // the page may have moved to another file since
				preview.put("timestamp", previewTimestamp);
				preview.put("content", getVersionContent(previewFile, previewTimestamp));
				sendMessageToSession(session, createMessage("version_content", objectMapper.writeValueAsString(preview)));
				break;

			case "list_text_versions": {
				// Same backups as JSON configs, but the path is relative to
				// _templates/, so resolve (and confine) it before listing.
				Path tvPath = resolveTextFilePath(jsonNode.get("fileName").asText());
				if (tvPath == null) {
					sendMessageToSession(session, createMessage("error", "Invalid path"));
				} else {
					sendMessageToSession(session,
							createMessage("text_version_list", listVersionsAt(tvPath)));
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
					// The path is already confined to _templates/; configPath()
					// would refuse it for not being a .json file.
					String rvContent;
					synchronized (SAVE_LOCKS.computeIfAbsent(String.valueOf(rvPath), k -> new Object())) {   // a restore is a write, as save_text_file
						rvContent = store.restore(rvPath, Long.parseLong(jsonNode.get("timestamp").asText()));
					}
					saved(session, "_templates/" + restoreName, "restored");
					sendMessageToSession(session,
							createMessage("text_version_restored", textVersionPayload(restoreName, rvContent)));
					if (getAutoPublish()) pushTemplate(session, restoreName);   // a restore is a save
					else ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);
				}
				break;
			}

			case "get_text_version_content": {
				String tvcName = jsonNode.get("fileName").asText();
				Path tvcPath = resolveTextFilePath(tvcName);
				if (tvcPath == null) {
					sendMessageToSession(session, createMessage("error", "Invalid path"));
				} else {
					String tvcContent = store.readVersion(tvcPath, Long.parseLong(jsonNode.get("timestamp").asText()));
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
				// An application name: "*" would publish every app on every server.
				if (!ValidationAPI.appName(reloadApp)) {
					sendMessageToSession(session, createMessage("publish_error", "Publish failed for " + reloadApp + ": not an application name"));
					break;
				}
				// Publish = push the saved files to every server, then reload:
				// a reload alone rereads each engine's own copy, which on a
				// layout where engines do not share the admin's disk is the old one.
				// A worker thread: pushing line by line to every engine can outlast
				// the page's ping timeout, which would drop this socket.
				new Thread(() -> {
					java.util.Map<String, Object> published = ValidationAPI.pushAndReload(reloadApp, "published");
					if (!Boolean.TRUE.equals(published.get("published"))) {
						Object why = published.containsKey("error") ? published.get("error") : published.get("message");
						sendMessageToSessionAsync(session, createMessage("publish_error", "Publish failed for " + reloadApp + ": " + why));
						return;
					}
					sendMessageToSessionAsync(session, createMessage("reload_success", "Configuration published for " + reloadApp));
					// Publish pushed every template too: say how that went, as
					// the `_templates` entry (pending while any server failed).
					if (published.containsKey("templatesFailed")) {   // none when no server runs the app
						sendMessageToSessionAsync(session, templatesReply((String) published.get("templates"),
								Boolean.TRUE.equals(published.get("templatesFailed"))));
					}
				}, "configurator-publish").start();
				break;

			case "publish_templates":
				// Templates saved while auto-publish was off: every one, to every server.
				requireWriter(session);
				new Thread(() -> {
					try {
						TemplatePush.Summary done = TemplatePush.pushAll(org.vorpal.blade.framework.v2.config.ConfigPublisher.domainRuntimeMBeanServer());
						sendMessageToSessionAsync(session, templatesReply(done.line, done.failed));
					} catch (Exception e) {
						logger.log(Level.WARNING, "template publish failed", e);
						try {
							sendMessageToSessionAsync(session, createMessage("publish_error", "Publish failed for _templates: " + e.getMessage()));
						} catch (Exception ignored) {
							// the page is gone
						}
					}
				}, "configurator-template-publish").start();
				break;

			case "get_unpublished":
				sendMessageToSession(session, createMessage("unpublished_list", objectMapper.writeValueAsString(ValidationAPI.UNPUBLISHED)));
				break;

			case "get_autopublish":
				sendMessageToSession(session, createMessage("autopublish_state", String.valueOf(getAutoPublish())));
				break;

			case "set_autopublish":
				requireWriter(session);
				boolean enabled = jsonNode.get("enabled").asBoolean();
				setAutoPublish(enabled);
				// Every open page, with the state read back (the merged one that
				// runs), not the one asked for: a failed reload shows as such.
				Boolean nowOn = readAutoPublish();
				broadcastMessage(createMessage("autopublish_state", String.valueOf(nowOn == null ? enabled : nowOn)));
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
				// Drafting spends the domain's API key: writers only, like Save.
				// Answered as ai_error, so the page's chat stops waiting.
				if (!access(session).writer) {
					sendMessageToSession(session, createMessage("ai_error", "Asking Claude needs the Admin or Operator role."));
					break;
				}
				String aiApp = jsonNode.get("appName").asText();
				// Echoed in the answer, so the page pairs it with its question.
				String aiRequest = jsonNode.path("requestId").asText("");
				String aiInstruction = jsonNode.get("instruction").asText();
				String aiBaseline = jsonNode.has("config") ? jsonNode.get("config").asText() : null;
				// The chat's earlier turns, so a follow-up reads the last answer.
				String aiHistory = jsonNode.has("history") ? jsonNode.get("history").toString() : null;
				String aiTarget = jsonNode.path("targetDirectory").asText("");
				boolean aiOverlay = aiTarget.contains("/_clusters/") || aiTarget.contains("/_servers/");
				// A generate call can run for a minute; answer from a worker
				// thread so this WebSocket keeps servicing pings meanwhile.
				new Thread(() -> {
					try {
						AiSettings ai = loadAiSettings();
						String schemaJson = loadSchemaFromFilesystem(aiApp);
						if (schemaJson == null || schemaJson.trim().isEmpty()) {
							sendMessageToSessionAsync(session, createMessage("ai_error", aiError(aiRequest, "Schema not found: " + aiApp)));
							return;
						}
						String result = AiConfigService.generate(ai, schemaJson, aiBaseline, aiInstruction, aiHistory, aiOverlay);
						com.fasterxml.jackson.databind.node.ObjectNode tagged = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(result);
						tagged.put("requestId", aiRequest);
						sendMessageToSessionAsync(session, createMessage("ai_result", objectMapper.writeValueAsString(tagged)));
					} catch (Throwable e) {
						// Throwable: an Error (a missing SDK class) would end this
						// thread with no answer, and the page would wait for good.
						logger.log(Level.WARNING, "AI generate failed for " + aiApp, e);
						sendMessageToSessionAsync(session, createMessage("ai_error", aiError(aiRequest, e.getMessage())));
					}
				}, "configurator-ai-generate").start();
				break;
			}

			default:
				sendMessageToSession(session, createMessage("error", "Unknown action: " + action));
			}

		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error processing WebSocket message", e);
			String why = "publish_templates".equals(action) ? "Publish failed for _templates: " + e.getMessage()
					: "Error processing request: " + e.getMessage();
			if ("load_error".equals(errorType(action))) {
				sendMessageToSession(session, loadError(requestSeq, why));
				return;
			}
			if (("version_error".equals(errorType(action)) || "text_error".equals(errorType(action))) && requestFile != null) {
				why += " (" + requestFile + ")";   // which file it was about
			}
			if ("save_json".equals(action)) {
				// Which file: a clone and a save can both be out.
				com.fasterxml.jackson.databind.node.ObjectNode failed = objectMapper.createObjectNode();
				failed.put("file", requestFile);
				failed.put("message", why);
				why = failed.toString();
			}
			sendMessageToSession(session, createMessage(errorType(action), why));
		}
	}

	/// The reply type of a failed request: the page waits on saves, loads and
	/// publishes, and must know which one a failure answers. Anything else is
	/// a plain `error`, which ends nothing the page is waiting on.
	static String errorType(String action) {
		if (action == null) return "error";
		switch (action) {
		case "save_json":
			return "save_error";
		case "save_text_file":
			return "text_save_error";
		case "set_autopublish":
			return "autopublish_error";
		case "load_schema":
		case "load_json":
		case "load_sample":
		case "resolve_json_file":
			return "load_error";
		case "reload":
		case "publish_templates":
			return "publish_error";
		case "restore_version":
		case "get_version_content":
		case "list_versions":
			return "version_error";
		case "load_text_file":
		case "restore_text_version":
		case "get_text_version_content":
		case "list_text_versions":
			return "text_error";
		default:
			return "error";
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

	/// A failed load, naming the page's load it answers (`seq`): an error for a
	/// load the operator has since replaced must not cancel the current one.
	private String loadError(String seq, String why) {
		com.fasterxml.jackson.databind.node.ObjectNode out = objectMapper.createObjectNode();
		out.put("seq", seq);
		out.put("message", why);
		return createMessage("load_error", out.toString());
	}

	/// The page's answer for the `_templates` entry of a Publish: a failed
	/// server keeps it pending (publish_error), else it is published.
	private String templatesReply(String line, boolean failed) {
		if (failed) ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);
		else ValidationAPI.watcherPublished(ValidationAPI.TEMPLATES);
		return failed ? createMessage("publish_error", "Publish failed for _templates: " + line)
				: createMessage("reload_success", (line == null ? "Templates already current; " : "Templates " + line + "; ")
						+ "Configuration published for _templates");
	}

	/// Every page learns the server's list of applications saved while
	/// auto-publish was off (ValidationAPI.UNPUBLISHED) whenever it changes:
	/// a page's own list is lost on a reload, and other pages never see it.
	private void broadcastUnpublished(String snapshot) {
		broadcastMessage(createMessage("unpublished_list", snapshot));
	}

	private static final Map<String, Object> APP_LOCKS = new ConcurrentHashMap<>();

	/// One lock per application, taken before its file's: a save's id check
	/// reads the application's other files (IdentityCheck).
	private static Object appLock(String file) {
		String app = appOfConfig(file);
		return APP_LOCKS.computeIfAbsent(app == null ? file : app, k -> new Object());
	}

	/// The application a config file belongs to (its name without `.json`), or null.
	private static String appOfConfig(String relativePath) {
		String name = Paths.get(writtenPath(relativePath)).getFileName().toString();
		return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : null;
	}

	/// The admin's `_templates/` folder.
	static String templatesDir() {
		return TEMPLATES_DIR;
	}

	/// Pushes template `name` to every server on a worker thread (JMX to each
	/// engine) and tells the page how it went (`text_file_pushed`).
	private void pushTemplate(Session session, String name) {
		new Thread(() -> {
			com.fasterxml.jackson.databind.node.ObjectNode out = objectMapper.createObjectNode();
			out.put("fileName", name);
			try {
				TemplatePush.Result r = TemplatePush.push(
						org.vorpal.blade.framework.v2.config.ConfigPublisher.domainRuntimeMBeanServer(), name);
				out.put("summary", r.summary());
				out.put("ok", r.failed.isEmpty() && r.noReceiver.isEmpty());
				if (!r.failed.isEmpty()) ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);
			} catch (Exception e) {
				logger.log(Level.WARNING, "template push failed for " + name, e);
				out.put("summary", "not pushed: " + e.getMessage());
				ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);
				out.put("ok", false);
			}
			try {
				sendMessageToSessionAsync(session, createMessage("text_file_pushed", objectMapper.writeValueAsString(out)));
			} catch (Exception e) {
				logger.log(Level.FINE, "push result not sent", e);
			}
		}, "configurator-template-push").start();
	}

	/// This server's own overlays of the Configurator's settings: its server
	/// file, and its cluster's when it is in one.
	private static java.util.List<Path> ownOverlays() {
		java.util.List<Path> out = new java.util.ArrayList<>();
		String server = System.getProperty("weblogic.Name");
		if (server == null || server.isEmpty()) return out;
		out.add(Paths.get(CONFIG_BASE, "_servers", server, SELF_APP + ".json"));
		try {
			String cluster = org.vorpal.blade.framework.v3.configuration.ClusterMembership.read().get(server);
			if (cluster != null) out.add(Paths.get(CONFIG_BASE, "_clusters", cluster, SELF_APP + ".json"));
		} catch (Exception e) {
			// membership unreadable: the server file only
		}
		return out;
	}

	/// Auto-publish on, as far as can be known: false when its state cannot be
	/// read, so a save is listed pending rather than taken for live.
	/// The flag alone is not enough: a watcher that failed to start, or
	/// stopped, pushes nothing, and a save must then be pending.
	private boolean autoPublishKnownOn() {
		return Boolean.TRUE.equals(readAutoPublish()) && ConfigurationMonitorStartup.watching();
	}

	/// A cluster or server file, not the domain one.
	private static boolean isOverlay(String file) {
		try {
			return !configPath(file).getParent().equals(Paths.get(CONFIG_BASE).toAbsolutePath().normalize());
		} catch (IOException e) {
			return false;
		}
	}

	/// Read the live auto-publish state from the Configurator's own MBean.
	/// Reads the merged in-memory config (which includes the shipped default
	/// when no domain file exists yet), so the UI toggle reflects what the
	/// running server is actually doing. Defaults to true if it can't be read.
	private boolean getAutoPublish() {
		Boolean on = readAutoPublish();
		return on == null || on;
	}

	/// The auto-publish state, or null when it cannot be read.
	private Boolean readAutoPublish() {
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
			return true;   // the shipped default
		} catch (Exception e) {
			logger.log(Level.WARNING, "could not read configurator auto-publish state", e);
			return null;
		}
	}

	/// Persist the auto-publish flag and republish so it takes effect live.
	/// Sets `autoPublish` in the domain `blade-configurator.json`, keeping its
	/// other keys (ConfiguratorSettingsManager seeds the file from the sample
	/// at startup; if it is gone, a file holding only this key is written), then reloads
	/// the Configurator's MBean — which fires ConfiguratorSettingsManager's
	/// initialize() hook and starts or stops the watcher thread.
	private void setAutoPublish(boolean enabled) throws Exception {
		Path file = Paths.get(CONFIG_BASE + "/" + SELF_APP + ".json");
		// Read and written under the file's lock: a form Save of this file
		// between the two would otherwise be overwritten without a conflict.
		synchronized (saveLock(file.toString())) {
			com.fasterxml.jackson.databind.node.ObjectNode node;
			if (Files.exists(file)) {
				JsonNode existing = objectMapper.readTree(Files.readAllBytes(file));
				node = existing.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) existing
						: objectMapper.createObjectNode();
			} else {
				node = objectMapper.createObjectNode();
			}
			node.put("autoPublish", enabled);
			saveConfigFile(file.toString(), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node), null, null, null);
		}
		// The state read is this server's merged one: an overlay of its own (its
		// server or cluster file) setting autoPublish would outvote the switch.
		for (Path overlay : ownOverlays()) {
			synchronized (saveLock(overlay.toString())) {
				if (!Files.isRegularFile(overlay)) continue;
				JsonNode o = objectMapper.readTree(Files.readAllBytes(overlay));
				if (o.isObject() && o.has("autoPublish")) {
					((com.fasterxml.jackson.databind.node.ObjectNode) o).remove("autoPublish");
					saveConfigFile(overlay.toString(), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(o), null, null, null);
					logger.info("auto-publish switch: removed autoPublish from " + overlay);
				}
			}
		}
		applyAutoPublish(enabled);
	}

	/// The Configurator's auto-publish state, just written to its file, put into
	/// effect: reload its settings (which starts or stops the watcher), then,
	/// when turned on, publish what was saved while it was off (by any page):
	/// the configurations recorded unpublished, then every template. The watcher
	/// first: a save landing after the list is read is then the watcher's.
	/// Called for the switch, and for a Save or Restore of the file itself.
	private void applyAutoPublish(boolean enabled) {
		try {
			reloadViaMBean(SELF_APP);
		} catch (Exception e) {
			// Not in effect: the file stays pending, and nothing is caught up.
			logger.log(Level.WARNING, "auto-publish state not applied", e);
			return;
		}
		ValidationAPI.watcherPublished(SELF_APP);   // its write is applied by this reload
		if (enabled) catchUp();
	}

	/// A file of the Configurator's own settings was written (any level: an
	/// overlay of this server can set autoPublish too). Its merged state, read
	/// after a reload, is what runs: put into effect, and every page told it.
	/// With auto-publish off nothing else would reload it (the watcher is
	/// stopped); turned on, what was saved while it was off is published.
	private void ownSettingsWritten() {
		Boolean before = readAutoPublish();
		try {
			reloadViaMBean(SELF_APP);
		} catch (Exception e) {
			logger.log(Level.WARNING, "auto-publish state not applied", e);
		}
		Boolean after = readAutoPublish();
		if (after == null) return;
		ValidationAPI.watcherPublished(SELF_APP);   // the reload is its publish: it runs here only
		if (after && !after.equals(before)) catchUp();
		broadcastMessage(createMessage("autopublish_state", String.valueOf(after)));
	}

	/// Auto-publish just turned on: the configurations recorded unpublished,
	/// then every template, published on a worker thread.
	private void catchUp() {
		new Thread(() -> {
			for (String app : new java.util.TreeSet<>(ValidationAPI.UNPUBLISHED)) {
				if (ValidationAPI.TEMPLATES.equals(app)) continue;   // pushed below
				java.util.Map<String, Object> done = ValidationAPI.pushAndReload(app, "published");
				if (!Boolean.TRUE.equals(done.get("published"))) {
					logger.warning("auto-publish on: " + app + " not published: " + done.get("error") + " " + done.get("message"));
				}
			}
			try {
				TemplatePush.Summary done = TemplatePush.pushAll(org.vorpal.blade.framework.v2.config.ConfigPublisher.domainRuntimeMBeanServer());
				if (done.line != null) logger.info("auto-publish on, templates: " + done.line);
				if (done.failed) ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);
				else ValidationAPI.watcherPublished(ValidationAPI.TEMPLATES);
			} catch (Exception e) {
				logger.log(Level.WARNING, "templates not pushed when auto-publish turned on", e);
			}
		}, "configurator-catchup").start();
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
		return new String(Files.readAllBytes(schemaPath), java.nio.charset.StandardCharsets.UTF_8);
	}

	private String loadSampleFromFilesystem(String appName) throws IOException {
		Path samplePath = Paths.get(SAMPLES_DIR, sanitizeName(appName) + ".json.SAMPLE");
		if (!Files.exists(samplePath)) {
			return null;
		}
		return new String(Files.readAllBytes(samplePath), java.nio.charset.StandardCharsets.UTF_8);
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
		return new String(Files.readAllBytes(templatePath), java.nio.charset.StandardCharsets.UTF_8);
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
					for (Path part : root.relativize(p)) {
						if (part.toString().startsWith(".")) return false;   // what Publish pushes
					}
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
		// Refused, not opened with its bad bytes replaced: a Save would write
		// the replacements, and the push would carry them to every server.
		try {
			return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
					.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
					.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
		} catch (java.nio.charset.CharacterCodingException e) {
			throw new IOException(relPath + " is not UTF-8 text: edit it on the server, or convert it to UTF-8");
		}
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
		// The template's version on disk now (after a restore: the restored one).
		payload.put("version", fileVersion(resolveTextFilePath(fileName)));
		return objectMapper.writeValueAsString(payload);
	}

	/// Resolve a caller-supplied relative path inside _templates/, rejecting
	/// any attempt to escape the root directory. Returns null for empty or
	/// obviously-bad input.
	private Path resolveTextFilePath(String relPath) {
		if (relPath == null || relPath.trim().isEmpty()) return null;
		for (Path part : Paths.get(relPath)) {
			// "..", and any dot folder or file (.versions/ backups, .archive/):
			// a segment, not "v1..2.sql". Publish skips those, so a Save must
			// not push one either.
			if (part.toString().startsWith(".")) return null;
		}
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
		// The page names files by the target paths this servlet hands it, which
		// start with CONFIG_BASE; with DOMAIN_HOME unset that is the relative
		// "./config/custom/vorpal", relative to the working directory like the
		// root itself. Only a bare name ("x.json") is relative to the root.
		// Resolving the first kind against the root nested a new file in
		// config/custom/vorpal/config/custom/vorpal.
		if (!candidate.isAbsolute()) {
			candidate = candidate.normalize().startsWith(Paths.get(CONFIG_BASE).normalize())
					? candidate.toAbsolutePath()
					: root.resolve(candidate);
		}
		candidate = candidate.toAbsolutePath().normalize();
		String name = candidate.getFileName().toString();
		if (!candidate.startsWith(root) || !(name.endsWith(".json") || name.endsWith(".json.SAMPLE"))) {
			throw new IOException("not a configuration file: " + requested);
		}
		// Named for an application: "*.json" would be a wildcard in the
		// watcher's MBean query, pushed as every application's file.
		String app = name.substring(0, name.indexOf(".json"));
		if (!ValidationAPI.appName(app)) throw new IOException("not an application's file: " + requested);
		// The folders too: a cluster or server folder's name goes into the
		// watcher's MBean query, where "*" would match every server.
		for (Path part : root.relativize(candidate.getParent())) {
			if (!part.toString().isEmpty() && !ValidationAPI.appName(part.toString()) && !part.toString().startsWith("_")) {
				throw new IOException("not a cluster or server folder: " + requested);
			}
		}
		// Only the places a configuration lives: the folder itself,
		// _clusters/<name>/, _servers/<name>/, and _samples/ for samples.
		Path where = root.relativize(candidate.getParent());
		String first = where.getNameCount() > 0 ? where.getName(0).toString() : "";
		boolean place = first.isEmpty()
				|| (where.getNameCount() == 2 && ("_clusters".equals(first) || "_servers".equals(first)))
				|| (where.getNameCount() == 1 && "_samples".equals(first));
		if (!place) throw new IOException("not a configuration's place: " + requested);
		return candidate;
	}

	private String loadConfigFile(String relativePath) throws IOException {
		Path filePath = configPath(relativePath);
		if (!Files.exists(filePath)) {
			throw new IOException("File does not exist: " + relativePath);
		}

		String text = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.UTF_8);
		// A byte-order mark: the engines' Jackson skips it, the page's
		// JSON.parse does not (the file would open as broken). A Save writes none.
		return text.startsWith("\uFEFF") ? text.substring(1) : text;
	}

	/// A file's version, as Save's conflict check compares it: modification
	/// time, size and a checksum of the content ("none" for a file that does
	/// not exist yet). Time and size alone repeat on
	/// a filesystem with whole-second times (NFS) when two saves land in one
	/// second with one length (a changed digit).
	static String fileVersion(Path file) {
		if (file == null) return "none";
		try {
			return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file) + ":" + checksum(file) : "none";
		} catch (IOException e) {
			return "unknown";
		}
	}

	/// CRC-32 of a file's bytes, in hex.
	static String checksum(Path file) throws IOException {
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(Files.readAllBytes(file));
		return Long.toHexString(crc.getValue());
	}

	private String versionJson(String relativePath) throws IOException {
		java.util.Map<String, Object> v = new java.util.LinkedHashMap<>();
		v.put("file", relativePath);
		v.put("version", fileVersion(configPath(relativePath)));
		return objectMapper.writeValueAsString(v);
	}

	/// The file a save of `relativePath` writes: a sample saves as its app's file.
	/// `text` parsed onto `sources`, when it is there and parses.
	private static void addSource(java.util.List<JsonNode> sources, String text, ObjectMapper mapper) {
		if (text == null || text.trim().isEmpty()) return;
		try {
			sources.add(mapper.readTree(text));
		} catch (IOException unparseable) {
			// a broken file is no source
		}
	}

	private static String readIfThere(Path file) throws IOException {
		return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8) : null;
	}

	private static String writtenPath(String relativePath) {
		if (relativePath.contains("/_samples/") && relativePath.endsWith(".json.SAMPLE")) {
			return relativePath.replace("/_samples/", "/").replace(".json.SAMPLE", ".json");
		}
		return relativePath;
	}

	/// What the application would fail to load with `content` written as
	/// `relativePath` ([IdentityCheck]), merged as its loader merges it; empty
	/// for a file that is no application's domain, cluster or server file.
	private java.util.List<String> identityProblems(String relativePath, String content) throws IOException {
		Path file = configPath(writtenPath(relativePath));
		Path rel = Paths.get(CONFIG_BASE).toAbsolutePath().normalize().relativize(file);
		String name = file.getFileName().toString();
		if (!name.endsWith(".json")) return java.util.Collections.emptyList();
		String app = name.substring(0, name.length() - ".json".length());
		String id;
		String place = null;   // the cluster or server the file is for
		if (rel.getNameCount() == 1) {
			id = org.vorpal.blade.framework.v3.configuration.ConfigFiles.DOMAIN;
		} else if (rel.getNameCount() == 3 && "_clusters".equals(rel.getName(0).toString())) {
			id = "cluster/" + rel.getName(1);
			place = rel.getName(1).toString();
		} else if (rel.getNameCount() == 3 && "_servers".equals(rel.getName(0).toString())) {
			id = "server/" + rel.getName(1);
			place = rel.getName(1).toString();
		} else {
			return java.util.Collections.emptyList();
		}
		java.util.List<String> out = new java.util.ArrayList<>();
		if (place != null) {
			// With no domain file the application runs on its sample; one
			// overlay saved now becomes the whole configuration of those
			// servers, the sample no longer under it.
			if (!Files.exists(Paths.get(CONFIG_BASE, app + ".json"))) {
				out.add("there is no Domain file for " + app + ": saved alone, this file would become the whole configuration of "
						+ place + " (the sample stops applying). Save the Domain file first.");
				return out;
			}
			// A folder left behind by a renamed server or cluster: no engine reads it.
			java.util.Set<String> names = domainPlaces(id.startsWith("cluster/"));
			boolean known = names == null || names.contains(place);
			if (!known) {
				out.add("this domain has no " + (id.startsWith("cluster/") ? "cluster" : "server") + " named " + place
						+ ": no server would read this file");
				return out;
			}
		}
		// No schema (or one that does not parse): the schema leg is skipped,
		// never the rest. Ids and the rule-set rename check need none.
		String schemaJson = loadSchemaFromFilesystem(app);
		JsonNode schema = null;
		try {
			schema = schemaJson == null ? null : objectMapper.readTree(schemaJson);
		} catch (IOException unparseableSchema) {
			logger.log(Level.WARNING, "schema of " + app + " does not parse; saved without the schema check", unparseableSchema);
		}
		JsonNode draft = objectMapper.readTree(content);
		java.util.Map<String, String> membership = org.vorpal.blade.framework.v3.configuration.ClusterMembership.forSave(app);
		// What this save breaks on the servers, less what the file on disk
		// already breaks (SaveCheck).
		// Null when the file does not parse (the broken file this save fixes),
		// or there is no Domain file: then nothing counts as already there.
		// A new cluster or server file starts from an empty one: merged over
		// the files below, that is what its servers run today, so a problem
		// of the Domain file is not this save's.
		JsonNode before = place != null && !Files.exists(file) ? objectMapper.createObjectNode() : null;
		try {
			if (Files.exists(file)) before = objectMapper.readTree(stripBom(new String(Files.readAllBytes(file),
					java.nio.charset.StandardCharsets.UTF_8)));
			if (before != null && !before.isObject()) before = null;
		} catch (IOException unparseable) {
			before = null;
		}
		java.util.Set<String> clusters = domainPlaces(true), servers = domainPlaces(false);
		java.util.function.Predicate<String> read = overlayId -> {   // a cluster or server some server reads
			String n = overlayId.substring(overlayId.indexOf('/') + 1);
			java.util.Set<String> names = overlayId.startsWith("cluster/") ? clusters : servers;
			return names == null || names.contains(n);
		};
		out.addAll(SaveCheck.added(app, id, draft, before, membership, objectMapper, runChecks(schema), read));
		return out;
	}

	/// The domain's cluster names (`clusters`) or server names (every server,
	/// clustered or not, and each dynamic cluster's), or null when they
	/// cannot be read (then nothing is judged stale).
	private static java.util.Set<String> domainPlaces(boolean clusters) {
		try {
			javax.management.MBeanServer dr = org.vorpal.blade.framework.v3.configuration.ClusterMembership.domainRuntime();
			javax.management.ObjectName config = (javax.management.ObjectName) dr.getAttribute(
					new javax.management.ObjectName(org.vorpal.blade.framework.v3.configuration.ClusterMembership.DOMAIN_RUNTIME_SERVICE),
					"DomainConfiguration");
			java.util.Set<String> out = new java.util.HashSet<>();
			if (!clusters) {
				for (javax.management.ObjectName sv : (javax.management.ObjectName[]) dr.getAttribute(config, "Servers")) {
					out.add(String.valueOf(dr.getAttribute(sv, "Name")));
				}
				out.addAll(org.vorpal.blade.framework.v3.configuration.ClusterMembership.of(dr).keySet());   // dynamic servers
			} else {
				for (javax.management.ObjectName c : (javax.management.ObjectName[]) dr.getAttribute(config, "Clusters")) {
					out.add(String.valueOf(dr.getAttribute(c, "Name")));
				}
			}
			return out;
		} catch (Exception e) {
			return null;
		}
	}

	/// What a whole configuration must satisfy before it goes live: its
	/// schema (required properties, types, the polymorphic type that the
	/// loader needs) and, where the schema's classes take identity, unique
	/// ids and references that resolve ([IdentityCheck]).
	static org.vorpal.blade.framework.v3.configuration.DraftCheck.Check runChecks(JsonNode schema) {
		com.networknt.schema.JsonSchema full = compiled(schema);
		boolean ids = IdentityCheck.usesIdentity(schema);
		return (doc, domainFile) -> {
			java.util.List<String> p = new java.util.ArrayList<>();
			if (full != null) {
				for (com.networknt.schema.ValidationMessage m : full.validate(doc)) {
					// The generated schemas never say "null", yet Jackson loads a
					// null into any field (an overlay clearing a value): not a problem.
					if (m.getMessage().contains(": null found,")) continue;
					p.add(m.getMessage());
				}
			}
			if (ids) p.addAll(IdentityCheck.problems(doc, schema));
			return p;
		};
	}

	/// A schema compiled once per content: a Save checks up to one document
	/// per overlay, and compiling is the slow part.
	private static final java.util.Map<String, com.networknt.schema.JsonSchema> COMPILED = new java.util.concurrent.ConcurrentHashMap<>();

	private static com.networknt.schema.JsonSchema compiled(JsonNode schema) {
		if (schema == null) return null;
		try {
			String key = schema.toString();
			if (COMPILED.size() > 200) COMPILED.clear();
			return COMPILED.computeIfAbsent(key, k -> AiConfigService.factoryFor(schema).getSchema(schema));
		} catch (RuntimeException unusable) {
			// A schema networknt cannot compile: saved without the schema check.
			logger.log(Level.WARNING, "schema not usable for validation", unusable);
			return null;
		}
	}

	private static String stripBom(String text) {
		return text.startsWith("\uFEFF") ? text.substring(1) : text;
	}

	/// Writes `content` as `relativePath`, repaired by [JsonRepair] against,
	/// most trusted first: `sourceText` (the exact text the page's form came
	/// from: the file as loaded, a sample, an import, a template), the form's
	/// own file `sourceFile` (a clone's source), the destination as it is, and
	/// `editorText` (the JSON tab's text: the page's own serialization, so
	/// last; it matters only when nothing else parses).
	private void saveConfigFile(String relativePath, String content, String sourceText, String sourceFile,
			String editorText) throws IOException {
		String realPath = writtenPath(relativePath);

		Path filePath = configPath(realPath);

		// Encrypt any {CLEARTEXT} credentials before writing to disk
		com.fasterxml.jackson.databind.ObjectMapper encMapper = new com.fasterxml.jackson.databind.ObjectMapper();
		com.fasterxml.jackson.databind.JsonNode tree = encMapper.readTree(content);
		// A secret typed in the JSON tab arrives bare: mark the app's password
		// fields first, so it is encrypted like a form-typed one. Optional: a
		// schema that cannot be read leaves only already-marked values to encrypt.
		boolean marked = false;
		try {
			String fileName = filePath.getFileName().toString();
			String schemaJson = loadSchemaFromFilesystem(fileName.substring(0, fileName.length() - ".json".length()));
			if (schemaJson != null) {
				JsonNode schemaRoot = encMapper.readTree(schemaJson);
				AiConfigService.markPasswordsForEncryption(tree, schemaRoot, schemaRoot);
				marked = !tree.equals(encMapper.readTree(content));
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "password fields not marked for " + filePath.getFileName() + ": " + e.getMessage());
		}
		// What the browser's JavaScript did to the document in passing (key
		// order, long integers): undone against the text the page's form came
		// from, else the destination as it is ([JsonRepair]).
		try {
			java.util.List<JsonNode> sources = new java.util.ArrayList<>();
			addSource(sources, sourceText, encMapper);
			if (sourceFile != null && !sourceFile.isEmpty()) {
				try {
					Path form = configPath(writtenPath(sourceFile));
					if (!form.equals(filePath)) addSource(sources, readIfThere(form), encMapper);
				} catch (IOException outside) {
					// not a config file: no source
				}
			}
			addSource(sources, readIfThere(filePath), encMapper);
			java.util.List<JsonNode> editor = new java.util.ArrayList<>();
			addSource(editor, editorText, encMapper);
			if (JsonRepair.repair(tree, sources, editor.isEmpty() ? null : editor.get(0))) {
				marked = true;   // write the repaired tree
			}
		} catch (IOException e) {
			logger.log(Level.FINE, "not repaired against the source of " + filePath.getFileName() + " (unreadable)", e);
		}
		// A secret encrypted by another domain (an import): this one cannot
		// decrypt it, so on the engines the credential would be broken. One the
		// file already holds is left alone (it is this file's own).
		refuseForeignSecrets(tree, filePath, encMapper);
		// Encryption itself is not optional: a secret is never written as
		// {CLEARTEXT}. A failure refuses the save (the page sees the error).
		{
			if (hasCleartext(tree) && !org.vorpal.blade.framework.v2.config.CredentialEncryption.isAvailable()) {
				throw new IOException("not saved: the domain's encryption service is not available to encrypt a secret");
			}
			// Where the secrets are before encryption, to check each after: the
			// framework hands back plaintext when encryption itself fails.
			java.util.List<String> secretPaths = new java.util.ArrayList<>();
			cleartextPaths(tree, "", secretPaths);
			boolean encrypted = org.vorpal.blade.framework.v2.config.CredentialEncryption.encryptTree(tree);
			for (String at : secretPaths) {
				String v = tree.at(at).asText("");
				if (!v.matches("^\\{(AES|AES256|3DES)\\}.*")) {
					throw new IOException("not saved: a secret could not be encrypted");
				}
			}
			if (encrypted || marked) {
				content = encMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
				System.out.println("Encrypted {CLEARTEXT} credentials in " + filePath.getFileName());
			}
			if (hasCleartext(encMapper.readTree(content))) {
				throw new IOException("not saved: a secret could not be encrypted");
			}
		}

		// Backs up existing content into .versions/, creates parent dirs, writes.
		store.write(filePath, content);
	}

	private static void refuseForeignSecrets(JsonNode tree, Path filePath, ObjectMapper mapper) throws IOException {
		if (!org.vorpal.blade.framework.v2.config.CredentialEncryption.isAvailable()) return;
		java.util.Set<String> known = new java.util.HashSet<>();
		if (Files.isRegularFile(filePath)) {
			try {
				collectStrings(mapper.readTree(Files.readAllBytes(filePath)), known);
			} catch (IOException unreadable) {
				// nothing known
			}
		}
		java.util.Set<String> mine = new java.util.HashSet<>();
		collectStrings(tree, mine);
		for (String v : mine) {
			if (!v.matches("^\\{(AES|AES256|3DES)\\}.+") || known.contains(v)) continue;
			if (v.equals(org.vorpal.blade.framework.v2.config.CredentialEncryption.decrypt(v))) {
				throw new IOException("not saved: a secret is encrypted by another domain and cannot be read here; type it again");
			}
		}
	}

	private static void collectStrings(JsonNode n, java.util.Set<String> out) {
		if (n == null) return;
		if (n.isTextual()) out.add(n.asText());
		else n.forEach(c -> collectStrings(c, out));
	}

	/// JSON Pointers of every string value marked {CLEARTEXT}.
	static void cleartextPaths(JsonNode node, String at, java.util.List<String> out) {
		if (node == null) return;
		if (node.isTextual()) {
			if (node.asText().startsWith("{CLEARTEXT}")) out.add(at);
		} else if (node.isObject()) {
			node.fieldNames().forEachRemaining(k -> cleartextPaths(node.get(k), at + "/" + k.replace("~", "~0").replace("/", "~1"), out));
		} else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) cleartextPaths(node.get(i), at + "/" + i, out);
		}
	}

	/// Any string value still marked {CLEARTEXT}: a secret not yet encrypted.
	static boolean hasCleartext(JsonNode node) {
		if (node == null) return false;
		if (node.isTextual()) return node.asText().startsWith("{CLEARTEXT}");
		for (JsonNode child : node) {
			if (hasCleartext(child)) return true;
		}
		return false;
	}

	private String listVersions(String relativePath) throws IOException {
		return listVersionsAt(configPath(relativePath));
	}

	/// The versions of a file already resolved and confined: a config through
	/// configPath, a template through resolveTextFilePath.
	private String listVersionsAt(Path filePath) throws IOException {
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
		String text = store.restore(configPath(relativePath), Long.parseLong(timestampStr));
		return text != null && text.startsWith("\uFEFF") ? text.substring(1) : text;
	}

	private String getVersionContent(String relativePath, String timestampStr) throws IOException {
		String text = store.readVersion(configPath(relativePath), Long.parseLong(timestampStr));
		// A byte-order mark, as on load: the page's JSON.parse refuses one.
		return text != null && text.startsWith("\uFEFF") ? text.substring(1) : text;
	}

	/// Whether `text` is one JSON object and nothing after it, as the page's
	/// JSON.parse reads it. Jackson's readTree stops at the first value, so
	/// `{..} {..}` or a stray `}` would pass it and then fail on the page.
	static boolean wholeObject(String text) throws IOException {
		if (text == null) return false;
		try (com.fasterxml.jackson.core.JsonParser p = objectMapper.getFactory().createParser(text)) {
			JsonNode tree = objectMapper.readTree(p);
			return tree != null && tree.isObject() && p.nextToken() == null;
		}
	}

	private String listTargetDirectories() throws IOException {
		java.util.List<java.util.Map<String, Object>> targetList = new java.util.ArrayList<>();

		// Which folders name a real cluster or server: one left behind by a
		// rename is listed, marked, so its file can still be read.
		java.util.Set<String> clusterNames = domainPlaces(true), serverNames = domainPlaces(false);

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
							clusterInfo.put("displayName", "Cluster: " + clusterName
									+ (clusterNames == null || clusterNames.contains(clusterName) ? "" : " (not in this domain)"));
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
							serverInfo.put("displayName", "Server: " + serverName
									+ (serverNames == null || serverNames.contains(serverName) ? "" : " (not in this domain)"));
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
			// Named, so the page can drop a reply to a load it has since replaced.
			result.put("schemaName", schemaName);
			result.put("targetDirectory", targetDirectory);
			// The version of the file Save would write ("none" before it exists),
			// so two operators creating one overlay do not race.
			result.put("version", fileVersion(jsonPath));
			return objectMapper.writeValueAsString(result);
		}

		// Only check for sample files if target is domain directory
		if (targetDirectory.equals(CONFIG_BASE)) {
			// Samples are <name>.json.SAMPLE (loadSampleFromFilesystem); a
			// ".json" here never matched, so an app with no file opened empty.
			Path samplePath = Paths.get(SAMPLES_DIR, sanitizeName(schemaName) + ".json.SAMPLE");
			if (Files.exists(samplePath)) {
				java.util.Map<String, Object> result = new java.util.HashMap<>();
				result.put("appName", schemaName);
				result.put("jsonFileType", "sample");
				result.put("exists", true);
				// Named, so the page can drop a reply to a load it has since replaced.
			result.put("schemaName", schemaName);
			result.put("targetDirectory", targetDirectory);
			// The version of the file Save would write ("none" before it exists),
			// so two operators creating one overlay do not race.
			result.put("version", fileVersion(jsonPath));
			return objectMapper.writeValueAsString(result);
			}
		}

		// No file found
		java.util.Map<String, Object> result = new java.util.HashMap<>();
		result.put("jsonFile", null);
		result.put("jsonFileType", null);
		result.put("exists", false);
		// Named, so the page can drop a reply to a load it has since replaced.
			result.put("schemaName", schemaName);
			result.put("targetDirectory", targetDirectory);
			// The version of the file Save would write ("none" before it exists),
			// so two operators creating one overlay do not race.
			result.put("version", fileVersion(jsonPath));
			return objectMapper.writeValueAsString(result);
	}

	/// Every send to a session, one at a time: replies come from the socket's
	/// own thread and from workers (publish, template push, the assistant), and
	/// the WebSocket API lets a send fail while another is in progress, which
	/// would lose the reply the page is waiting on.
	private void sendMessageToSession(Session session, String message) {
		synchronized (session) {
			try {
				if (session.isOpen()) {
					session.getBasicRemote().sendText(message);
				}
			} catch (IOException | RuntimeException e) {
				logger.log(Level.SEVERE, "Error sending message to session " + session.getId(), e);
			}
		}
	}

	/// Send from a worker thread: the same serialized send.
	private void sendMessageToSessionAsync(Session session, String message) {
		sendMessageToSession(session, message);
	}

	/// The AI settings: the domain's shared `blade-ai.json` when it exists
	/// ([AiConfigService#settings]), else the Configurator's own `ai` section.
	private AiSettings loadAiSettings() {
		return AiConfigService.settings();
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

	/// Handshake results waiting for their socket to open, keyed by user and
	/// the socket URL's query: the page adds a random `k` to it. Keyed by user
	/// alone, a cross-site socket could pick up the flag of the same user's
	/// real tab; it cannot know that tab's `k`. onOpen moves the result onto
	/// the session, so each socket keeps its own.
	private static final Map<String, Access> HANDSHAKES = new ConcurrentHashMap<>();
	/// An ai_error naming the request it answers.
	private String aiError(String requestId, String message) {
		try {
			com.fasterxml.jackson.databind.node.ObjectNode e = objectMapper.createObjectNode();
			e.put("requestId", requestId);
			e.put("error", message);
			return objectMapper.writeValueAsString(e);
		} catch (Exception x) {
			return message;
		}
	}

	/// One save at a time per file, around the version check and the write.
	private static final Map<String, Object> SAVE_LOCKS = new ConcurrentHashMap<>();

	/// The lock for a config file, by its resolved path (one per file, whatever
	/// the spelling of the request).
	private static Object saveLock(String file) throws IOException {
		return SAVE_LOCKS.computeIfAbsent(configPath(file).toString(), k -> new Object());
	}
	/// When each waiting handshake was made; one whose socket never opened is
	/// dropped after a minute, so the map does not grow.
	private static final Map<String, Long> HANDSHAKE_TIMES = new ConcurrentHashMap<>();
	private static final String ACCESS_KEY = "blade.configurator.access";

	private static String handshakeKey(String user, String query) {
		return user + "|" + (query == null ? "" : query);
	}

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
			long now = System.currentTimeMillis();
			HANDSHAKE_TIMES.entrySet().removeIf(e -> {
				if (now - e.getValue() <= 60_000L) return false;
				HANDSHAKES.remove(e.getKey());
				return true;
			});
			String key = handshakeKey(user.getName(), request.getQueryString());
			HANDSHAKES.put(key, new Access(writer, sameOrigin));
			HANDSHAKE_TIMES.put(key, now);
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

	/// Record who changed which file ([ConfigEvents#saved]).
	private static void saved(Session session, String file, String change) {
		Principal user = session.getUserPrincipal();
		ConfigEvents.saved((user == null) ? null : user.getName(), file, "configurator", change);
	}

	private static Access access(Session session) {
		Object a = (session.getUserPrincipal() != null) ? session.getUserProperties().get(ACCESS_KEY) : null;
		return (a instanceof Access) ? (Access) a : new Access(false, false);
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
