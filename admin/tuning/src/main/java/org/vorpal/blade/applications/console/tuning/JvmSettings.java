package org.vorpal.blade.applications.console.tuning;

import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.Context;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.applications.console.tuning.ApplyPlan.Change;
import org.vorpal.blade.applications.console.tuning.ServerStartTargets.Kind;
import org.vorpal.blade.applications.console.tuning.ServerStartTargets.Target;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.SettingsMXBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API for JVM profiles: named sets of Server-Start JVM arguments, assigned to targets
/// and overlaid onto each target's `ServerStart.Arguments`, the field Node Manager reads at
/// startup. Profiles and assignments persist in the app's own config
/// (`config/custom/vorpal/blade-tuning.json`) via its Settings MBean.
///
/// A **target** is a ServerStart owner in config.xml (see [ServerStartTargets]): a static
/// server or a server template, never a dynamic engine. Every write here goes through
/// [#withEdit], which records the live state to the history file before the edit session
/// opens, and the first read pins the install-time state as the baseline (see
/// [ServerStartSnapshot]). `preview` returns the per-target diff an apply would make;
/// `restore` writes a baseline or history entry back verbatim.
///
/// The read-only GET (per-target parsed args) feeds the dashboard's Health Check.
@Path("/jvm")
@Tag(name = "JVM", description = "JVM profiles, targets, baseline and history")
public class JvmSettings {

	private static final Logger logger = Logger.getLogger(JvmSettings.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	/// Where SettingsManager keeps per-app config, relative to the server's working dir
	/// (DOMAIN_HOME). Must match `SettingsManager.CONFIG_BASE_PATH`.
	static final String CONFIG_BASE_PATH = "config/custom/vorpal";

	@javax.ws.rs.core.Context
	private ServletContext servletContext;

	private final ServerStartSnapshot.Store store = new ServerStartSnapshot.Store(Paths.get(CONFIG_BASE_PATH));

	// Modeled valued ("scalar") arguments: JSON field name -> argument prefix, in
	// form order. A token "owns" a field when it startsWith the prefix; the value
	// is whatever follows. Adding a new knob = one row here + one field in the
	// form. Anything not listed here (or in the flag/collector lists below) is
	// reported as an "additional" arg, so this list need not be exhaustive: it
	// only governs which args get a dedicated form field.
	private static final String[][] SCALARS = {
			{"heapInitial", "-Xms"},
			{"heapMax", "-Xmx"},
			{"metaspaceSize", "-XX:MetaspaceSize="},
			{"maxMetaspaceSize", "-XX:MaxMetaspaceSize="},
			{"maxGcPauseMillis", "-XX:MaxGCPauseMillis="},
			{"parallelGcThreads", "-XX:ParallelGCThreads="},
			{"concGcThreads", "-XX:ConcGCThreads="},
			{"compileThreshold", "-XX:CompileThreshold="},
			{"enableAssertions", "-ea:"},
			{"wlssMaddrEnable", "-Dwlss.maddr.enable="},
			{"wlssReplication", "-Dwlss.replication="},
			{"sslMinProtocol", "-Dweblogic.security.SSL.minimumProtocolVersion="},
			{"allowedPackagesSecure", "-Dweblogic.servlet.ClasspathServlet.allowedPackagesInSecureMode="},
			{"callStateManager", "-Dwlss.callstate.manager.classname="},
			{"systemClassLoader", "-Djava.system.class.loader="},
			{"launchUseEnvClasspath", "-Dlaunch.use.env.classpath="},
	};

	// Known GC collector flags
	private static final String[] GC_COLLECTORS = {
			"-XX:+UseG1GC", "-XX:+UseZGC", "-XX:+UseShenandoahGC",
			"-XX:+UseParallelGC", "-XX:+UseConcMarkSweepGC"
	};

	// Known boolean flags we track. The egd setting is a fixed-value system
	// property treated as an on/off toggle: pointing SecureRandom's seed source
	// at the non-blocking /dev/urandom avoids entropy-starvation stalls on
	// startup and TLS handshakes. The "/./" is the long-standing JDK workaround:
	// "file:/dev/urandom" is read as the special token and silently falls back to
	// blocking /dev/random, while "file:/dev/./urandom" is treated as a plain
	// path and read non-blocking.
	private static final String[] KNOWN_BOOLEAN_FLAGS = {
			"-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers",
			"-XX:+HeapDumpOnOutOfMemoryError", "-XX:+UseStringDeduplication",
			"-server", "-da", "-XX:+DisableExplicitGC",
			"-Djava.security.egd=file:/dev/./urandom",
			// Low-pause latency flags (JDK 21). ZGenerational opts ZGC into its
			// generational mode, REQUIRED on JDK 21 (plain -XX:+UseZGC selects
			// the legacy non-generational collector); it became default in JDK 23
			// and is removed in JDK 24, so it's a JDK-21/22-only flag.
			// AlwaysPreTouch pages the whole heap in at startup and, with
			// -Xms=-Xmx, disables ZGC's runtime uncommit (both reduce pause jitter).
			"-XX:+ZGenerational", "-XX:+AlwaysPreTouch"
	};

	// ---- reads ------------------------------------------------------------------------------

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Parsed JVM arguments for every target (static servers and templates)")
	public Response getAllJvmSettings() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = domainRuntime(ctx);
			List<Target> targets = liveTargets(mbs);
			ensureBaseline(targets);

			ArrayNode result = mapper.createArrayNode();
			for (Target t : targets) {
				ObjectNode n = parseArguments(t.name, t.arguments);
				n.put("kind", t.kindName());
				n.put("javaHome", t.javaHome);
				n.put("javaVendor", t.javaVendor);
				n.put("classPath", t.classPath);
				ArrayNode members = n.putArray("members");
				for (String m : t.members) members.add(m);
				result.add(n);
			}
			return Response.ok(mapper.writeValueAsString(result)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@GET
	@Path("/servers")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Target names (static servers and templates)")
	public Response getServers() {
		try (CloseableContext ctx = new CloseableContext()) {
			ArrayNode result = mapper.createArrayNode();
			for (Target t : liveTargets(domainRuntime(ctx))) {
				result.add(t.name);
			}
			return Response.ok(mapper.writeValueAsString(result)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@GET
	@Path("/targets")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Every ServerStart owner with its live settings and baseline status")
	public Response getTargets() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = domainRuntime(ctx);
			ObjectName dc = ServerStartTargets.runtimeDomainConfig(mbs);
			List<Target> targets = ServerStartTargets.list(mbs, dc);
			ensureBaseline(targets);
			ServerStartSnapshot baseline = store.readBaseline();

			ObjectNode out = mapper.createObjectNode();
			out.put("adminServer", ServerStartTargets.adminServerName(mbs, dc));
			out.put("baselineCapturedAt", baseline == null ? "" : baseline.getCapturedAt());
			out.put("baselineReason", baseline == null ? "" : baseline.getReason());
			ArrayNode arr = out.putArray("targets");
			for (Target t : targets) {
				ObjectNode n = arr.addObject();
				n.put("name", t.name);
				n.put("kind", t.kindName());
				n.put("cluster", t.cluster);
				n.put("machine", t.machine);
				ArrayNode members = n.putArray("members");
				for (String m : t.members) members.add(m);
				n.put("classPath", t.classPath);
				n.put("arguments", t.arguments);
				n.put("javaHome", t.javaHome);
				n.put("javaVendor", t.javaVendor);
				n.put("hasServerStart", t.serverStart != null);
				ServerStartSnapshot.Entry b = baseline == null ? null : baseline.getTargets().get(t.name);
				n.put("inBaseline", b != null);
				n.put("argumentsDiffer", b != null && !ApplyPlan.sameArguments(b.getArguments(), t.arguments));
				n.put("classPathDiffers", b != null && !ApplyPlan.sameClassPath(b.getClassPath(), t.classPath));
				String cpWarn = ApplyPlan.classPathWarning(t.classPath);
				if (cpWarn != null) n.put("classPathWarning", cpWarn);
			}

			// Assignments that name something which is not a target (a dynamic engine from
			// before templates were modeled, a deleted server) so the UI can say so once.
			ArrayNode orphans = out.putArray("orphanedAssignments");
			for (String assigned : resolveAssignmentNames(currentConfig()).keySet()) {
				if (ServerStartTargets.find(targets, assigned) == null) orphans.add(assigned);
			}
			return Response.ok(mapper.writeValueAsString(out)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@GET
	@Path("/baseline")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "The pinned baseline: every target's ServerStart as install.sh left it")
	public Response getBaseline() {
		try (CloseableContext ctx = new CloseableContext()) {
			ensureBaseline(liveTargets(domainRuntime(ctx)));
			ServerStartSnapshot b = store.readBaseline();
			if (b == null) {
				return Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"no baseline captured\"}").build();
			}
			return Response.ok(mapper.writeValueAsString(b)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@POST
	@Path("/baseline")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Re-baseline: pin the live ServerStart of every target as the new baseline")
	public Response rebaseline() {
		try (CloseableContext ctx = new CloseableContext()) {
			ServerStartSnapshot s = ServerStartSnapshot.capture(liveTargets(domainRuntime(ctx)), "operator re-baseline");
			store.writeBaseline(s);
			return Response.ok(mapper.writeValueAsString(s)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@GET
	@Path("/history")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "The retained pre-write snapshots, newest first")
	public Response getHistory() {
		try {
			ArrayNode out = mapper.createArrayNode();
			for (ServerStartSnapshot s : store.listHistory()) {
				ObjectNode n = out.addObject();
				n.put("id", s.getCapturedAtMillis());
				n.put("capturedAt", s.getCapturedAt());
				n.put("reason", s.getReason());
				ArrayNode t = n.putArray("targets");
				for (String name : s.getTargets().keySet()) t.add(name);
			}
			return Response.ok(mapper.writeValueAsString(out)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@GET
	@Path("/history/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "One pre-write snapshot, by its capturedAtMillis id")
	public Response getHistoryEntry(@PathParam("id") long id) {
		try {
			ServerStartSnapshot s = store.readHistory(id);
			if (s == null) {
				return Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"no such snapshot\"}").build();
			}
			return Response.ok(mapper.writeValueAsString(s)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	// ---- profiles ---------------------------------------------------------------------------

	@GET
	@Path("/profiles")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get all JVM profiles and per-target assignments")
	public Response getProfiles() {
		try {
			ObjectNode cfg = currentConfig();
			ObjectNode out = mapper.createObjectNode();
			out.set("profiles", cfg.has("jvmProfiles") ? cfg.get("jvmProfiles") : mapper.createArrayNode());
			out.set("assignments",
					cfg.has("jvmProfileAssignments") ? cfg.get("jvmProfileAssignments") : mapper.createObjectNode());
			return Response.ok(mapper.writeValueAsString(out)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@PUT
	@Path("/profiles")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Save JVM profiles and per-target assignments")
	public Response putProfiles(String body) {
		try {
			ObjectNode in = (ObjectNode) mapper.readTree(body);
			ObjectNode cfg = currentConfig();
			cfg.set("jvmProfiles", in.has("profiles") ? in.get("profiles") : mapper.createArrayNode());
			cfg.set("jvmProfileAssignments",
					in.has("assignments") ? in.get("assignments") : mapper.createObjectNode());

			// Persist to config/custom/vorpal/blade-tuning.json via the app's own
			// Settings MBean: the same open/write/close/reload path the watcher
			// uses. REST is the browser-to-server boundary; this is server-local JMX.
			SettingsMXBean settings = settingsProxy();
			settings.openForWrite("DOMAIN");
			settings.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg));
			settings.close();
			settings.reload();

			return Response.ok("{\"success\":true}").build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	// ---- preview / apply --------------------------------------------------------------------

	@POST
	@Path("/preview")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "What apply would change on each assigned target, without an edit session")
	public Response preview() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = domainRuntime(ctx);
			ObjectName dc = ServerStartTargets.runtimeDomainConfig(mbs);
			List<Target> targets = ServerStartTargets.list(mbs, dc);
			List<Change> changes = plan(mbs, dc, targets, currentConfig());
			return Response.ok(mapper.writeValueAsString(changesJson(changes, false))).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@POST
	@Path("/apply")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Overlay each assigned profile onto its target's ServerStart.Arguments")
	public Response apply() {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode cfg = currentConfig();
			Map<String, String> names = resolveAssignmentNames(cfg);
			if (names.isEmpty()) {
				return Response.ok(mapper.writeValueAsString(changesJson(new ArrayList<>(), false))).build();
			}

			List<Change> changes = new ArrayList<>();
			withEdit(ctx, "before apply: " + describeAssignments(names), (edit, dc, targets) -> {
				// One edit session for the whole cluster. A profile is a tuning OVERLAY, not a
				// replacement for the target's whole JVM line: in MBean mode setDomainEnv.sh never
				// runs, so ServerStart.Arguments is the only place -Dwls.home, -Dweblogic.home, the
				// debugpatch javaagent and -Dwlss.callstate.manager.classname exist. Overwriting
				// verbatim silently dropped whichever of those a profile did not restate, which
				// stops the server booting or quietly disables SIP call-state replication.
				changes.addAll(plan(edit, dc, targets, cfg));
				for (Change c : changes) {
					if (!c.ok || c.isUnchanged()) continue; // no knob moves: leave config.xml alone
					try {
						ServerStartTargets.write(edit, ServerStartTargets.find(targets, c.target), null, c.after);
					} catch (Exception ex) {
						c.ok = false;
						c.error = String.valueOf(ex.getMessage());
					}
				}
			});
			return Response.ok(mapper.writeValueAsString(changesJson(changes, true))).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	// ---- restore / classpath ----------------------------------------------------------------

	@POST
	@Path("/restore")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Write a snapshot's ClassPath and Arguments back onto targets, verbatim")
	public Response restore(String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode in = (ObjectNode) mapper.readTree(body == null || body.isEmpty() ? "{}" : body);
			String source = in.path("source").asText("baseline");
			ServerStartSnapshot snap = "baseline".equals(source) ? store.readBaseline()
					: store.readHistory(Long.parseLong(source));
			if (snap == null) {
				return Response.status(Response.Status.NOT_FOUND)
						.entity("{\"error\":\"no such snapshot: " + escapeJson(source) + "\"}").build();
			}
			List<String> wanted = new ArrayList<>();
			if (in.has("targets") && in.get("targets").isArray()) {
				for (JsonNode n : in.get("targets")) wanted.add(n.asText());
			}
			if (wanted.isEmpty()) wanted.addAll(snap.getTargets().keySet());

			ObjectNode out = mapper.createObjectNode();
			out.put("success", true);
			out.put("requiresRestart", true);
			out.put("source", source);
			out.put("capturedAt", snap.getCapturedAt());
			ArrayNode results = out.putArray("restored");
			withEdit(ctx, "before restore from " + ("baseline".equals(source) ? "baseline" : snap.getCapturedAt()),
					(edit, dc, targets) -> {
						for (String name : wanted) {
							ObjectNode r = results.addObject();
							r.put("target", name);
							ServerStartSnapshot.Entry entry = snap.getTargets().get(name);
							Target t = ServerStartTargets.find(targets, name);
							if (entry == null) {
								r.put("ok", false).put("error", "not in that snapshot");
							} else if (t == null) {
								r.put("ok", false).put("error", "no such target in the domain");
							} else {
								try {
									// Verbatim, not merged: a restore means "exactly what it was".
									ServerStartTargets.write(edit, t, entry.getClassPath(), entry.getArguments());
									r.put("ok", true);
									r.put("classPath", entry.getClassPath());
									r.put("arguments", entry.getArguments());
								} catch (Exception ex) {
									r.put("ok", false).put("error", String.valueOf(ex.getMessage()));
								}
							}
						}
					});
			return Response.ok(mapper.writeValueAsString(out)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@PUT
	@Path("/targets/{name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Set one target's ServerStart.ClassPath")
	public Response putTarget(@PathParam("name") String name, String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode in = (ObjectNode) mapper.readTree(body == null || body.isEmpty() ? "{}" : body);
			if (!in.has("classPath")) {
				return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"classPath is required\"}").build();
			}
			String classPath = in.get("classPath").asText("").trim();
			withEdit(ctx, "before classpath edit on " + name, (edit, dc, targets) -> {
				Target t = ServerStartTargets.find(targets, name);
				if (t == null) throw new IllegalArgumentException("no such target: " + name);
				ServerStartTargets.write(edit, t, classPath, null);
			});
			ObjectNode out = mapper.createObjectNode();
			out.put("success", true);
			out.put("requiresRestart", true);
			out.put("target", name);
			out.put("classPath", classPath);
			String warn = ApplyPlan.classPathWarning(classPath);
			if (warn != null) out.put("warning", warn);
			return Response.ok(mapper.writeValueAsString(out)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	// ---- the plan ---------------------------------------------------------------------------

	/// The per-target changes the current assignments would make, with warnings, against the
	/// targets as read from `mbs` (the runtime tree for a preview, the edit tree for an apply).
	List<Change> plan(MBeanServer mbs, ObjectName domainConfig, List<Target> targets, ObjectNode cfg) throws Exception {
		Map<String, String> names = resolveAssignmentNames(cfg);
		Map<String, String> args = resolveAssignments(cfg);
		String adminName = ServerStartTargets.adminServerName(mbs, domainConfig);
		Target admin = ServerStartTargets.find(targets, adminName);
		String adminMachine = admin == null ? "" : admin.machine;

		List<Change> changes = new ArrayList<>();
		for (Map.Entry<String, String> e : names.entrySet()) {
			String targetName = e.getKey();
			String profileName = e.getValue();
			Target t = ServerStartTargets.find(targets, targetName);
			if (t == null) {
				changes.add(Change.failed(targetName, "", "not a ServerStart owner: a dynamic engine takes its"
						+ " settings from its cluster's template, so assign the profile to the template"));
				continue;
			}
			if (t.serverStart == null) {
				changes.add(Change.failed(targetName, t.kindName(), "no ServerStart MBean"));
				continue;
			}
			// Resolve ${server}/${cluster}/${machine}/${domain} for THIS target, the identity the
			// AdminServer can derive from config MBeans, so a shared profile can hold
			// node-specific paths (e.g. a JFR filename).
			String resolved = resolveServerVars(mbs, domainConfig, t, args.get(profileName));
			List<String> kept = new ArrayList<>();
			String merged = mergeArguments(t.arguments, resolved, kept);
			Change c = ApplyPlan.diff(t.name, t.kindName(), profileName, t.arguments, merged, kept);
			ApplyPlan.warnMetaspaceCap(c);
			ApplyPlan.warnTemplateServerVar(c);
			String cpWarn = ApplyPlan.classPathWarning(t.classPath);
			if (cpWarn != null) c.warnings.add(cpWarn);
			changes.add(c);
		}

		// The admin box hosts the AdminServer and every static server on its machine (engine0).
		// Sum what they would pin after the apply, changed or not, against the RAM this JVM sees.
		Map<String, String> adminBox = new LinkedHashMap<>();
		for (Target t : targets) {
			if (t.kind != Kind.SERVER) continue;
			if (!t.machine.equals(adminMachine)) continue;
			String after = t.arguments;
			for (Change c : changes) {
				if (c.ok && c.target.equals(t.name)) after = c.after;
			}
			adminBox.put(t.name, after);
		}
		String heapWarn = ApplyPlan.adminBoxHeapWarning(adminBox, ramTotalMB());
		if (heapWarn != null) {
			for (Change c : changes) {
				if (c.ok && adminBox.containsKey(c.target)) c.warnings.add(heapWarn);
			}
		}
		return changes;
	}

	private ObjectNode changesJson(List<Change> changes, boolean applied) {
		ObjectNode out = mapper.createObjectNode();
		out.put("success", true);
		out.put("applied", applied);
		out.put("requiresRestart", applied);
		ArrayNode arr = out.putArray("changes");
		for (Change c : changes) arr.add(c.toJson(mapper));
		return out;
	}

	private static String describeAssignments(Map<String, String> names) {
		// profile -> targets, e.g. "G1GC - Java 11+ -> engine0, engine-template"
		Map<String, List<String>> byProfile = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : names.entrySet()) {
			byProfile.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : byProfile.entrySet()) {
			parts.add(e.getKey() + " -> " + String.join(", ", e.getValue()));
		}
		return String.join("; ", parts);
	}

	// ---- edit session + snapshots -----------------------------------------------------------

	/// The body of an edit session, handed the Edit tree's root and its targets.
	interface EditBody {
		void run(MBeanServer edit, ObjectName domainConfig, List<Target> targets) throws Exception;
	}

	/// Every write to a ServerStart goes through here: record the live (activated) state to the
	/// history file, open one edit session, run the body, save and activate. On any exception the
	/// session is undone and closed, then the exception propagates. The snapshot is taken from the
	/// DomainRuntime tree, not the edit tree, so it is what Node Manager would use right now, not
	/// someone's unactivated pending edit.
	void withEdit(Context ctx, String reason, EditBody body) throws Exception {
		MBeanServer runtime = domainRuntime(ctx);
		List<Target> live = liveTargets(runtime);
		ensureBaseline(live);
		store.recordHistory(ServerStartSnapshot.capture(live, reason));

		MBeanServer edit = EditMBeans.edit(ctx);
		ObjectName configManager = EditMBeans.configManager();
		edit.invoke(configManager, "startEdit", new Object[]{0, 120000}, new String[]{"int", "int"});
		try {
			ObjectName dc = ServerStartTargets.editDomainConfig(edit);
			List<Target> targets = ServerStartTargets.list(edit, dc);
			body.run(edit, dc, targets);
			edit.invoke(configManager, "save", null, null);
			edit.invoke(configManager, "activate", new Object[]{120000L}, new String[]{"long"});
		} catch (Exception e) {
			edit.invoke(configManager, "undoUnactivatedChanges", null, null);
			edit.invoke(configManager, "stopEdit", null, null);
			throw e;
		}
	}

	/// Pin the baseline if none exists. The first caller after install.sh sees exactly what
	/// install.sh wrote; nothing rewrites it but an explicit re-baseline.
	void ensureBaseline(List<Target> live) {
		try {
			if (store.hasBaseline()) return;
			store.writeBaseline(ServerStartSnapshot.capture(live, "first sight of the domain"));
			logger.info("Pinned the ServerStart baseline: " + store.baselineFile().toAbsolutePath());
		} catch (Exception e) {
			logger.log(Level.WARNING, "Could not pin the ServerStart baseline", e);
		}
	}

	/// Startup hook: try to pin the baseline before anyone can change anything. Returns false when
	/// the DomainRuntime MBeanServer is not reachable yet (the AdminServer is still booting), in
	/// which case the first read pins it instead.
	static boolean captureBaselineIfMissing(java.nio.file.Path configDir) {
		ServerStartSnapshot.Store s = new ServerStartSnapshot.Store(configDir);
		try {
			if (s.hasBaseline()) return true;
		} catch (RuntimeException e) {
			return false;
		}
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			List<Target> live = ServerStartTargets.list(mbs, ServerStartTargets.runtimeDomainConfig(mbs));
			s.writeBaseline(ServerStartSnapshot.capture(live, "first sight of the domain"));
			logger.info("Pinned the ServerStart baseline at startup: " + s.baselineFile().toAbsolutePath());
			return true;
		} catch (Exception e) {
			logger.info("ServerStart baseline not pinned at startup (" + e.getMessage() + "); the first read will");
			return false;
		}
	}

	private static MBeanServer domainRuntime(Context ctx) throws Exception {
		return (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
	}

	private static List<Target> liveTargets(MBeanServer runtime) throws Exception {
		return ServerStartTargets.list(runtime, ServerStartTargets.runtimeDomainConfig(runtime));
	}

	/// Physical RAM of the box this JVM (the AdminServer) runs on, in MB; -1 if unreadable.
	private static long ramTotalMB() {
		try {
			Object v = ManagementFactory.getPlatformMBeanServer()
					.getAttribute(new ObjectName("java.lang:type=OperatingSystem"), "TotalPhysicalMemorySize");
			return v instanceof Number ? ((Number) v).longValue() / (1024L * 1024L) : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	// ---- parsing / merging ------------------------------------------------------------------

	/// Parse a JVM arguments string into structured fields.
	ObjectNode parseArguments(String serverName, String arguments) {
		ObjectNode node = mapper.createObjectNode();
		node.put("server", serverName);

		List<String> tokens = tokenize(arguments);

		// Modeled valued args: value is whatever follows the prefix.
		for (String[] spec : SCALARS) {
			node.put(spec[0], scalarValue(tokens, spec[1]));
		}

		// GC collector (first match wins).
		String gcCollector = "";
		for (String t : tokens) {
			for (String gc : GC_COLLECTORS) {
				if (t.equals(gc)) { gcCollector = gc; break; }
			}
			if (!gcCollector.isEmpty()) break;
		}
		node.put("gcCollector", gcCollector);

		// Known boolean flags.
		ObjectNode flags = mapper.createObjectNode();
		for (String flag : KNOWN_BOOLEAN_FLAGS) {
			flags.put(flag, tokens.contains(flag));
		}
		node.set("flags", flags);

		// Everything we don't model, preserved in original order.
		StringBuilder additional = new StringBuilder();
		for (String t : tokens) {
			if (!isKnown(t)) {
				if (additional.length() > 0) additional.append(' ');
				additional.append(t);
			}
		}
		node.put("additionalArgs", additional.toString());

		// Also store the raw string.
		node.put("rawArguments", arguments);

		return node;
	}

	/// True if a token is owned by one of the modeled fields/flags/collectors.
	private boolean isKnown(String token) {
		for (String[] spec : SCALARS) {
			if (token.startsWith(spec[1])) return true;
		}
		for (String gc : GC_COLLECTORS) {
			if (token.equals(gc)) return true;
		}
		for (String flag : KNOWN_BOOLEAN_FLAGS) {
			if (token.equals(flag)) return true;
		}
		return false;
	}

	/// Split an arguments string into tokens on whitespace, preserving order.
	static List<String> tokenize(String args) {
		List<String> tokens = new ArrayList<>();
		if (args != null) {
			for (String t : args.trim().split("\\s+")) {
				if (!t.isEmpty()) tokens.add(t);
			}
		}
		return tokens;
	}

	/// Value of the first token that starts with `prefix`, or "".
	private String scalarValue(List<String> tokens, String prefix) {
		for (String t : tokens) {
			if (t.startsWith(prefix)) return t.substring(prefix.length());
		}
		return "";
	}

	/// Resolve target -> arguments from the saved config: for each assignment whose profile
	/// still exists, map the target to that profile's argument string. Static + package-private
	/// so it's unit-testable without a domain.
	static Map<String, String> resolveAssignments(ObjectNode cfg) {
		Map<String, String> byProfile = profileArguments(cfg);
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : resolveAssignmentNames(cfg).entrySet()) {
			out.put(e.getKey(), byProfile.get(e.getValue()));
		}
		return out;
	}

	/// Resolve target -> profile NAME for each assignment whose profile still exists.
	static Map<String, String> resolveAssignmentNames(ObjectNode cfg) {
		Map<String, String> byProfile = profileArguments(cfg);
		Map<String, String> out = new LinkedHashMap<>();
		JsonNode assignments = cfg.get("jvmProfileAssignments");
		if (assignments != null && assignments.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> it = assignments.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				String profileName = e.getValue().asText("");
				if (byProfile.containsKey(profileName)) {
					out.put(e.getKey(), profileName);
				}
			}
		}
		return out;
	}

	private static Map<String, String> profileArguments(ObjectNode cfg) {
		Map<String, String> byProfile = new LinkedHashMap<>();
		JsonNode profiles = cfg.get("jvmProfiles");
		if (profiles != null && profiles.isArray()) {
			for (JsonNode p : profiles) {
				String name = p.path("name").asText("");
				if (!name.isEmpty()) byProfile.put(name, p.path("arguments").asText(""));
			}
		}
		return byProfile;
	}

	/// Overlay `profile` onto the target's `existing` ServerStart arguments.
	///
	/// A profile carries the tuning knobs an operator wants to control; it is not the server's
	/// whole JVM command line. In MBean mode Node Manager builds that line from ServerStart alone,
	/// setDomainEnv.sh never runs, so `existing` is the only home for the platform baseline:
	/// `-Dwls.home`, `-Dweblogic.home`, the debugpatch `-javaagent`, and
	/// `-Dwlss.callstate.manager.classname`, which is the class that actually implements
	/// replicated SIP call state. Replacing rather than overlaying dropped whichever of those a
	/// profile happened not to restate: a missing wls.home stops the server booting, and a
	/// missing call-state manager disables failover quietly, which is worse.
	///
	/// Overlay rule: a profile token replaces any existing token with the same *key* (see
	/// [#argumentKey]), and every other existing token is kept. Setting a garbage collector is
	/// special-cased: collectors are mutually exclusive, so naming one removes all the others.
	///
	/// @param kept populated with the existing tokens the profile did not mention, for reporting
	/// @return the merged argument line
	String mergeArguments(String existing, String profile, List<String> kept) {
		List<String> profileTokens = tokenize(profile);
		if (existing == null || existing.trim().isEmpty()) {
			return String.join(" ", profileTokens);
		}

		java.util.Set<String> overridden = new java.util.HashSet<>();
		boolean profileSetsCollector = false;
		for (String token : profileTokens) {
			overridden.add(argumentKey(token));
			for (String gc : GC_COLLECTORS) {
				if (gc.equals(token)) profileSetsCollector = true;
			}
		}

		List<String> merged = new ArrayList<>();
		for (String token : tokenize(existing)) {
			if (overridden.contains(argumentKey(token))) {
				continue; // the profile is authoritative for this knob
			}
			if (profileSetsCollector && isCollector(token)) {
				continue; // only one collector may be active
			}
			merged.add(token);
			if (kept != null) kept.add(token);
		}
		merged.addAll(profileTokens);
		return String.join(" ", merged);
	}

	private static boolean isCollector(String token) {
		for (String gc : GC_COLLECTORS) {
			if (gc.equals(token)) return true;
		}
		return false;
	}

	/// The identity of a JVM argument for overlay purposes: what makes two tokens "the same knob".
	///
	/// `-Xmx512m` and `-Xmx4g` are the same knob; so are `-XX:+AlwaysPreTouch` and
	/// `-XX:-AlwaysPreTouch`, and `-Dwlss.replication=on` and `=off`. Anything unrecognised is
	/// its own key, so it is never silently displaced.
	static String argumentKey(String token) {
		for (String prefix : new String[]{"-Xms", "-Xmx", "-Xss", "-Xlog", "-javaagent", "-agentlib"}) {
			if (token.startsWith(prefix)) return prefix;
		}
		if (token.startsWith("-XX:+") || token.startsWith("-XX:-")) {
			return "-XX:" + token.substring(5);
		}
		if (token.startsWith("-XX:") && token.indexOf('=') > 0) {
			return "-XX:" + token.substring(4, token.indexOf('='));
		}
		if (token.startsWith("-D") && token.indexOf('=') > 0) {
			return token.substring(0, token.indexOf('='));
		}
		return token;
	}

	/// Substitute the per-target identity variables into a profile's argument string using
	/// BLADE's `${var}` resolver. Only the vars derivable from config MBeans on the AdminServer
	/// are supplied: `server`, `cluster`, `machine`, `domain`. Unknown placeholders are left
	/// literal, and the node's own runtime env/sysprops are deliberately NOT used (they would
	/// resolve to the AdminServer's, which would be wrong). For a template target `server` is
	/// left unresolved: one line serves every dynamic engine (see
	/// [ApplyPlan#warnTemplateServerVar]).
	private String resolveServerVars(MBeanServer mbs, ObjectName domainConfig, Target t, String args) {
		Map<String, String> attrs = new java.util.HashMap<>();
		if (t.kind == Kind.SERVER) attrs.put("server", t.name);
		attrs.put("cluster", t.cluster);
		attrs.put("machine", t.machine);
		try {
			attrs.put("domain", String.valueOf(mbs.getAttribute(domainConfig, "Name")));
		} catch (Exception ignore) {
		}
		return org.vorpal.blade.framework.v2.config.Configuration.resolveVariables(attrs, args);
	}

	/// Current Tuning config as a JSON tree, read from the app's Settings MBean.
	private ObjectNode currentConfig() throws Exception {
		String json = settingsProxy().getCurrentJson();
		if (json == null || json.isEmpty()) return mapper.createObjectNode();
		return (ObjectNode) mapper.readTree(json);
	}

	/// Local MXBean proxy to the Tuning app's own domain-scoped config MBean.
	private SettingsMXBean settingsProxy() throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		// The config MBean is registered under the FLATTENED CONTEXT PATH
		// (blade/tuning -> blade-tuning), not the display-name. Derive it exactly
		// the way SettingsManager registered it, or the lookup misses.
		String name = SettingsManager.deriveName(servletContext);
		ObjectName on = new ObjectName("vorpal.blade:Name=" + name + ",Type=Configuration");
		return javax.management.JMX.newMXBeanProxy(mbs, on, SettingsMXBean.class);
	}

	private Response serverError(Exception e) {
		return Response.serverError()
				.entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
				.build();
	}

	private String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static class CloseableContext extends javax.naming.InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException {
			super();
		}
	}
}
