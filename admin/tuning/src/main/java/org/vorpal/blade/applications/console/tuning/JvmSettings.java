package org.vorpal.blade.applications.console.tuning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for managing JVM settings on WebLogic managed servers.
 *
 * JVM arguments are stored in ServerStartMBean.Arguments as a free-form string.
 * This API parses known flags into structured fields and preserves any additional
 * flags the user has set manually.
 */
@Path("/api/v1/jvm")
@Tag(name = "JVM", description = "JVM and server start configuration")
public class JvmSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	// Patterns for extracting known JVM flags from the arguments string
	private static final Pattern XMS_PATTERN = Pattern.compile("-Xms(\\S+)");
	private static final Pattern XMX_PATTERN = Pattern.compile("-Xmx(\\S+)");
	private static final Pattern METASPACE_PATTERN = Pattern.compile("-XX:MetaspaceSize=(\\S+)");
	private static final Pattern MAX_METASPACE_PATTERN = Pattern.compile("-XX:MaxMetaspaceSize=(\\S+)");
	private static final Pattern MAX_GC_PAUSE_PATTERN = Pattern.compile("-XX:MaxGCPauseMillis=(\\S+)");
	private static final Pattern GC_THREADS_PATTERN = Pattern.compile("-XX:ParallelGCThreads=(\\S+)");
	private static final Pattern CONC_GC_THREADS_PATTERN = Pattern.compile("-XX:ConcGCThreads=(\\S+)");

	// Known GC collector flags
	private static final String[] GC_COLLECTORS = {
			"-XX:+UseG1GC", "-XX:+UseZGC", "-XX:+UseShenandoahGC",
			"-XX:+UseParallelGC", "-XX:+UseConcMarkSweepGC"
	};

	// Known boolean flags we track
	private static final String[] KNOWN_BOOLEAN_FLAGS = {
			"-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers",
			"-XX:+HeapDumpOnOutOfMemoryError", "-XX:+UseStringDeduplication",
			"-server", "-XX:+DisableExplicitGC"
	};

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get JVM settings for all managed servers")
	public Response getAllJvmSettings() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName domainConfig = new ObjectName("com.bea:Name=DomainConfiguration,Type=Domain");
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

	@PUT
	@Path("/{serverName}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update JVM settings for a specific server")
	public Response setJvmSettings(@PathParam("serverName") String serverName, String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode input = (ObjectNode) mapper.readTree(body);
			String arguments = buildArguments(input);

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				ObjectName serverConfig = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
				ObjectName serverStart = (ObjectName) editMbs.getAttribute(serverConfig, "ServerStart");

				if (serverStart != null) {
					editMbs.setAttribute(serverStart,
							new javax.management.Attribute("Arguments", arguments));

					// Set JavaHome if provided
					if (input.has("javaHome") && !input.get("javaHome").asText().isEmpty()) {
						editMbs.setAttribute(serverStart,
								new javax.management.Attribute("JavaHome", input.get("javaHome").asText()));
					}
				}

				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				return Response.ok("{\"success\":true,\"server\":\"" + escapeJson(serverName)
						+ "\",\"arguments\":\"" + escapeJson(arguments) + "\"}").build();

			} catch (Exception e) {
				editMbs.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMbs.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}

		} catch (Exception e) {
			return Response.serverError()
					.entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
					.build();
		}
	}

	/**
	 * Parse a JVM arguments string into structured fields.
	 */
	ObjectNode parseArguments(String serverName, String arguments) {
		ObjectNode node = mapper.createObjectNode();
		node.put("server", serverName);

		// Extract known flags
		node.put("heapInitial", extractPattern(arguments, XMS_PATTERN));
		node.put("heapMax", extractPattern(arguments, XMX_PATTERN));
		node.put("metaspaceSize", extractPattern(arguments, METASPACE_PATTERN));
		node.put("maxMetaspaceSize", extractPattern(arguments, MAX_METASPACE_PATTERN));
		node.put("maxGcPauseMillis", extractPattern(arguments, MAX_GC_PAUSE_PATTERN));
		node.put("parallelGcThreads", extractPattern(arguments, GC_THREADS_PATTERN));
		node.put("concGcThreads", extractPattern(arguments, CONC_GC_THREADS_PATTERN));

		// Detect GC collector
		String gcCollector = "";
		for (String gc : GC_COLLECTORS) {
			if (arguments.contains(gc)) {
				gcCollector = gc;
				break;
			}
		}
		node.put("gcCollector", gcCollector);

		// Detect known boolean flags
		ObjectNode flags = mapper.createObjectNode();
		for (String flag : KNOWN_BOOLEAN_FLAGS) {
			flags.put(flag, arguments.contains(flag));
		}
		node.set("flags", flags);

		// Collect everything that wasn't parsed into a known field
		String remaining = arguments;
		remaining = XMS_PATTERN.matcher(remaining).replaceAll("");
		remaining = XMX_PATTERN.matcher(remaining).replaceAll("");
		remaining = METASPACE_PATTERN.matcher(remaining).replaceAll("");
		remaining = MAX_METASPACE_PATTERN.matcher(remaining).replaceAll("");
		remaining = MAX_GC_PAUSE_PATTERN.matcher(remaining).replaceAll("");
		remaining = GC_THREADS_PATTERN.matcher(remaining).replaceAll("");
		remaining = CONC_GC_THREADS_PATTERN.matcher(remaining).replaceAll("");
		for (String gc : GC_COLLECTORS) {
			remaining = remaining.replace(gc, "");
		}
		for (String flag : KNOWN_BOOLEAN_FLAGS) {
			remaining = remaining.replace(flag, "");
		}
		node.put("additionalArgs", remaining.trim().replaceAll("\\s+", " "));

		// Also store the raw string for reference
		node.put("rawArguments", arguments);

		return node;
	}

	/**
	 * Build a JVM arguments string from structured fields.
	 */
	String buildArguments(ObjectNode input) {
		StringBuilder sb = new StringBuilder();

		appendIfPresent(sb, input, "heapInitial", "-Xms");
		appendIfPresent(sb, input, "heapMax", "-Xmx");
		appendIfPresent(sb, input, "metaspaceSize", "-XX:MetaspaceSize=");
		appendIfPresent(sb, input, "maxMetaspaceSize", "-XX:MaxMetaspaceSize=");
		appendIfPresent(sb, input, "maxGcPauseMillis", "-XX:MaxGCPauseMillis=");
		appendIfPresent(sb, input, "parallelGcThreads", "-XX:ParallelGCThreads=");
		appendIfPresent(sb, input, "concGcThreads", "-XX:ConcGCThreads=");

		// GC collector
		if (input.has("gcCollector") && !input.get("gcCollector").asText().isEmpty()) {
			sb.append(input.get("gcCollector").asText()).append(" ");
		}

		// Boolean flags
		if (input.has("flags")) {
			ObjectNode flags = (ObjectNode) input.get("flags");
			flags.fieldNames().forEachRemaining(flag -> {
				if (flags.get(flag).asBoolean()) {
					sb.append(flag).append(" ");
				}
			});
		}

		// Additional args
		if (input.has("additionalArgs") && !input.get("additionalArgs").asText().isEmpty()) {
			sb.append(input.get("additionalArgs").asText()).append(" ");
		}

		return sb.toString().trim();
	}

	private String extractPattern(String input, Pattern pattern) {
		Matcher m = pattern.matcher(input);
		return m.find() ? m.group(1) : "";
	}

	private void appendIfPresent(StringBuilder sb, ObjectNode input, String field, String flag) {
		if (input.has(field) && !input.get(field).asText().isEmpty()) {
			sb.append(flag).append(input.get(field).asText()).append(" ");
		}
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
