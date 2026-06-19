package org.vorpal.blade.applications.console.tuning;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.config.SettingsMXBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for JVM "profiles" — named, complete sets of Server-Start JVM
 * arguments. A profile is authored once and assigned to cluster nodes; Apply
 * writes each assigned node's profile into its ServerStart.Arguments (the field
 * Node Manager reads at startup). Profiles + assignments persist in the Tuning
 * app's own config (config/custom/vorpal/tuning.json) via its Settings MBean.
 *
 * The read-only GET (per-server parsed args) is retained for the dashboard's
 * Health Check, which inspects each server's live arguments.
 */
@Path("/jvm")
@Tag(name = "JVM", description = "JVM profiles and per-node assignment")
public class JvmSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	@Context
	private ServletContext servletContext;

	// Modeled valued ("scalar") arguments: JSON field name -> argument prefix, in
	// form order. A token "owns" a field when it startsWith the prefix; the value
	// is whatever follows. Adding a new knob = one row here + one field in the
	// form. Anything not listed here (or in the flag/collector lists below) is
	// preserved verbatim as an "additional" arg, so this list need not be
	// exhaustive — it only governs which args get a dedicated form field.
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
	// startup and TLS handshakes. The "/./" is the long-standing JDK workaround —
	// "file:/dev/urandom" is read as the special token and silently falls back to
	// blocking /dev/random, while "file:/dev/./urandom" is treated as a plain
	// path and read non-blocking.
	private static final String[] KNOWN_BOOLEAN_FLAGS = {
			"-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers",
			"-XX:+HeapDumpOnOutOfMemoryError", "-XX:+UseStringDeduplication",
			"-server", "-da", "-XX:+DisableExplicitGC",
			"-Djava.security.egd=file:/dev/./urandom",
			// Low-pause latency flags (JDK 21). ZGenerational opts ZGC into its
			// generational mode — REQUIRED on JDK 21 (plain -XX:+UseZGC selects
			// the legacy non-generational collector); it became default in JDK 23
			// and is removed in JDK 24, so it's a JDK-21/22-only flag.
			// AlwaysPreTouch pages the whole heap in at startup and, with
			// -Xms=-Xmx, disables ZGC's runtime uncommit (both reduce pause jitter).
			"-XX:+ZGenerational", "-XX:+AlwaysPreTouch"
	};

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get JVM settings for all managed servers")
	public Response getAllJvmSettings() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			// Use DomainRuntimeServiceMBean.DomainConfiguration — direct
			// "Name=DomainConfiguration,Type=Domain" lookup throws on WLS 14.1.1.
			// Memory: [[wls-domain-jmx-bootstrap]].
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domainConfig, "Servers");

			ArrayNode result = mapper.createArrayNode();

			for (ObjectName server : servers) {
				String serverName = (String) mbs.getAttribute(server, "Name");
				ObjectName serverStart = (ObjectName) mbs.getAttribute(server, "ServerStart");

				String arguments = "";
				String javaHome = "";
				String javaVendor = "";

				if (serverStart != null) {
					Object args = mbs.getAttribute(serverStart, "Arguments");
					Object home = mbs.getAttribute(serverStart, "JavaHome");
					Object vendor = mbs.getAttribute(serverStart, "JavaVendor");
					if (args != null) arguments = args.toString();
					if (home != null) javaHome = home.toString();
					if (vendor != null) javaVendor = vendor.toString();
				}

				ObjectNode serverNode = parseArguments(serverName, arguments);
				serverNode.put("javaHome", javaHome);
				serverNode.put("javaVendor", javaVendor);
				result.add(serverNode);
			}

			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return Response.serverError()
					.entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
					.build();
		}
	}

	@GET
	@Path("/servers")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List all server names in the domain")
	public Response getServers() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domainConfig, "Servers");
			ArrayNode result = mapper.createArrayNode();
			for (ObjectName server : servers) {
				result.add((String) mbs.getAttribute(server, "Name"));
			}
			return Response.ok(mapper.writeValueAsString(result)).build();
		} catch (Exception e) {
			return serverError(e);
		}
	}

	@GET
	@Path("/profiles")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get all JVM profiles and per-node assignments")
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
	@Operation(summary = "Save JVM profiles and per-node assignments")
	public Response putProfiles(String body) {
		try {
			ObjectNode in = (ObjectNode) mapper.readTree(body);
			ObjectNode cfg = currentConfig();
			cfg.set("jvmProfiles", in.has("profiles") ? in.get("profiles") : mapper.createArrayNode());
			cfg.set("jvmProfileAssignments",
					in.has("assignments") ? in.get("assignments") : mapper.createObjectNode());

			// Persist to config/custom/vorpal/tuning.json via the app's own
			// Settings MBean — the same open/write/close/reload path the watcher
			// uses. REST is the browser↔server boundary; this is server-local JMX.
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

	@POST
	@Path("/apply")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Write each assigned profile into its server's ServerStart.Arguments")
	public Response apply() {
		try (CloseableContext ctx = new CloseableContext()) {
			Map<String, String> resolved = resolveAssignments(currentConfig());

			ObjectNode out = mapper.createObjectNode();
			out.put("success", true);
			ArrayNode results = mapper.createArrayNode();
			out.set("applied", results);

			if (resolved.isEmpty()) {
				return Response.ok(mapper.writeValueAsString(out)).build();
			}

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");
			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				// One edit session for the whole cluster; each assigned server's
				// ServerStart.Arguments is overwritten with its profile, verbatim.
				for (Map.Entry<String, String> e : resolved.entrySet()) {
					ObjectNode r = results.addObject();
					r.put("server", e.getKey());
					try {
						ObjectName serverConfig = new ObjectName("com.bea:Name=" + e.getKey() + ",Type=Server");
						ObjectName serverStart = (ObjectName) editMbs.getAttribute(serverConfig, "ServerStart");
						if (serverStart != null) {
							// Resolve ${server}/${cluster}/${machine}/${domain} for THIS node — the
							// per-node identity the AdminServer can derive from config MBeans — so a
							// shared profile can hold node-specific paths (e.g. JFR filename).
							String resolvedArgs = resolveServerVars(editMbs, serverConfig, e.getKey(), e.getValue());
							editMbs.setAttribute(serverStart,
									new javax.management.Attribute("Arguments", resolvedArgs));
							r.put("ok", true);
							r.put("arguments", resolvedArgs);
						} else {
							r.put("ok", false);
							r.put("error", "no ServerStart MBean");
						}
					} catch (Exception ex) {
						r.put("ok", false);
						r.put("error", String.valueOf(ex.getMessage()));
					}
				}

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				return Response.ok(mapper.writeValueAsString(out)).build();

			} catch (Exception e) {
				editMbs.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMbs.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}

		} catch (Exception e) {
			return serverError(e);
		}
	}

	/**
	 * Parse a JVM arguments string into structured fields.
	 */
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

		// Everything we don't model, preserved in original order — uses the same
		// isKnown() test as applyArguments so parse and apply never disagree.
		StringBuilder additional = new StringBuilder();
		for (String t : tokens) {
			if (!isKnown(t)) {
				if (additional.length() > 0) additional.append(' ');
				additional.append(t);
			}
		}
		node.put("additionalArgs", additional.toString());

		// Also store the raw string for the hand-edit ("Arguments") tab.
		node.put("rawArguments", arguments);

		return node;
	}

	/** True if a token is owned by one of the modeled fields/flags/collectors. */
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

	/** Split an arguments string into tokens on whitespace, preserving order. */
	private List<String> tokenize(String args) {
		List<String> tokens = new ArrayList<>();
		if (args != null) {
			for (String t : args.trim().split("\\s+")) {
				if (!t.isEmpty()) tokens.add(t);
			}
		}
		return tokens;
	}

	/** Value of the first token that starts with {@code prefix}, or "". */
	private String scalarValue(List<String> tokens, String prefix) {
		for (String t : tokens) {
			if (t.startsWith(prefix)) return t.substring(prefix.length());
		}
		return "";
	}

	/**
	 * Resolve server -> arguments from the saved config: for each assignment
	 * whose profile still exists, map the server to that profile's argument
	 * string. Static + package-private so it's unit-testable without a domain.
	 */
	static Map<String, String> resolveAssignments(ObjectNode cfg) {
		Map<String, String> byProfile = new LinkedHashMap<>();
		JsonNode profiles = cfg.get("jvmProfiles");
		if (profiles != null && profiles.isArray()) {
			for (JsonNode p : profiles) {
				String name = p.path("name").asText("");
				if (!name.isEmpty()) byProfile.put(name, p.path("arguments").asText(""));
			}
		}
		Map<String, String> out = new LinkedHashMap<>();
		JsonNode assignments = cfg.get("jvmProfileAssignments");
		if (assignments != null && assignments.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> it = assignments.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				String profileName = e.getValue().asText("");
				if (byProfile.containsKey(profileName)) {
					out.put(e.getKey(), byProfile.get(profileName));
				}
			}
		}
		return out;
	}

	/**
	 * Substitute the per-node identity variables into a profile's argument string
	 * using BLADE's {@code ${var}} resolver. Only the vars derivable from config
	 * MBeans on the AdminServer are supplied: {@code server}, {@code cluster},
	 * {@code machine}, {@code domain}. Unknown placeholders are left literal, and
	 * the node's own runtime env/sysprops are deliberately NOT used (they'd resolve
	 * to the AdminServer's, which would be wrong).
	 */
	private String resolveServerVars(MBeanServer editMbs, ObjectName serverConfig, String serverName, String args) {
		java.util.Map<String, String> attrs = new java.util.HashMap<>();
		attrs.put("server", serverName);
		attrs.put("cluster", "");
		attrs.put("machine", "");
		try {
			ObjectName c = (ObjectName) editMbs.getAttribute(serverConfig, "Cluster");
			if (c != null) attrs.put("cluster", String.valueOf(editMbs.getAttribute(c, "Name")));
		} catch (Exception ignore) {
		}
		try {
			ObjectName m = (ObjectName) editMbs.getAttribute(serverConfig, "Machine");
			if (m != null) attrs.put("machine", String.valueOf(editMbs.getAttribute(m, "Name")));
		} catch (Exception ignore) {
		}
		try {
			ObjectName editService = new ObjectName(
					"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
			ObjectName dc = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");
			attrs.put("domain", String.valueOf(editMbs.getAttribute(dc, "Name")));
		} catch (Exception ignore) {
		}
		return org.vorpal.blade.framework.v2.config.Configuration.resolveVariables(attrs, args);
	}

	/** Current Tuning config as a JSON tree, read from the app's Settings MBean. */
	private ObjectNode currentConfig() throws Exception {
		String json = settingsProxy().getCurrentJson();
		if (json == null || json.isEmpty()) return mapper.createObjectNode();
		return (ObjectNode) mapper.readTree(json);
	}

	/** Local MXBean proxy to the Tuning app's own domain-scoped config MBean. */
	private SettingsMXBean settingsProxy() throws Exception {
		MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
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
