package org.vorpal.blade.applications.callflow;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v3.events.AccessEvent;
import org.vorpal.blade.framework.v3.events.Events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST endpoints for the trace timeline — the "which app in the chain
/// misbehaved" view.
///
/// Backed by the per-app Trace MBeans
/// (`vorpal.blade:Name=<app>,Type=Trace[,Cluster=…]`, framework
/// `v3.diagnostics`). Unlike the Source reads — which are pinned to one idle
/// node because every node's copy is identical — trace reads sweep EVERY
/// federated instance: a call's steps live on whichever node handled it. The
/// browser merges all steps into per-call timelines by `X-Vorpal-Session`.
///
/// Arming is fan-out: `arm`/`disarm`/`clear` invoke the operation on every
/// Trace MBean in the domain, which is what makes one operator action arm the
/// entire app chain.
@javax.ws.rs.Path("/traces")
@Tag(name = "Call Traces", description = "Read and arm chain-aware call traces")
public class TracesAPI {

	private static final Logger log = Logger.getLogger(TracesAPI.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String JMX_DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	private static final String TRACE_PATTERN = "vorpal.blade:Name=*,Type=Trace,*";

	@javax.ws.rs.core.Context
	private javax.ws.rs.core.SecurityContext security;

	@javax.ws.rs.core.Context
	private javax.servlet.http.HttpServletRequest request;

	/// What the person making this request may see ([TraceAccess]).
	private TraceAccess viewer() {
		CallflowSettings settings = CallflowStartup.settings();
		org.vorpal.blade.framework.v3.access.Access access = org.vorpal.blade.framework.v3.access.Access
				.forRequest(request, (settings == null) ? null : settings.getGrants(),
						(settings == null) ? null : settings.getAccess());
		return new TraceAccess(access, org.vorpal.blade.framework.v2.config.SettingsManager.getDomainName());
	}

	/// What this viewer sees, in words, and whether they read every call they
	/// see: for the page to say so, and to offer the number search only to
	/// those who may use it. `{"sees":[...], "content":true|false}`.
	@GET
	@javax.ws.rs.Path("/access")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Describe what the signed-in viewer may see.")
	public Response access() {
		TraceAccess viewer = viewer();
		ObjectNode out = mapper.createObjectNode();
		ArrayNode sees = out.putArray("sees");
		for (String line : org.vorpal.blade.framework.v3.access.Access.describe(viewer.access())) {
			// Servers are the dashboard's; Trace shows calls only.
			if (!line.startsWith("Servers:")) {
				sees.add(line.replace("Transcripts and callers:", "Message text and callers:"));
			}
		}
		out.put("content", viewer.seesContent());
		out.put("operator", Operators.is(security));
		out.put("limited", !viewer.seesAllCalls() || !viewer.seesContent());
		return Response.ok(out.toString()).build();
	}

	/// Record a read of call content in the access log: a trace captures whole
	/// SIP messages and a log can hold caller numbers, so who looked is worth
	/// the same record a recording gets. Explicit acts only: the page's own
	/// polling (a trace refresh, a log follow) would bury them.
	private void accessed(String action, String kind, String id) {
		java.security.Principal user = (security == null) ? null : security.getUserPrincipal();
		String role = "authenticated";
		for (String candidate : new String[] { "Admin", "Operator", "Deployer", "Monitor" }) {
			if (security != null && security.isUserInRole(candidate)) {
				role = candidate;
				break;
			}
		}
		Events.publish(AccessEvent.granted((user == null) ? null : user.getName(), action, kind, id, role)
				.toCloudEvent(Events.source()));
	}

	/// Every node's buffered steps, per app:
	/// `{"sources":[{"app","server","steps":[...]}]}`. The browser aggregates.
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Collect buffered trace steps from every app on every node.")
	public Response traces() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(JMX_DOMAIN_RUNTIME);
			ObjectNode out = mapper.createObjectNode();
			ArrayNode arr = out.putArray("sources");
			for (ObjectName on : traceMBeans(mbs)) {
				try {
					JsonNode parsed = mapper.readTree((String) mbs.getAttribute(on, "StepsJson"));
					ObjectNode src = arr.addObject();
					src.put("app", on.getKeyProperty("Name"));
					src.put("server", location(on));
					src.set("steps", parsed.get("steps"));
				} catch (Exception ex) {
					log.log(Level.FINE, "steps read failed for " + on + ": " + ex.getMessage());
				}
			}
			viewer().apply(out);
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Calls the framework's flight recorder wrote out because they went wrong,
	/// in the shape [#traces] returns, newest first: one call's records with
	/// `call` (its Vorpal-ID, as the dashboard links it), else the latest
	/// `limit` records (default 50, at most 500) that pass the filters. `since`
	/// and `before` are epoch millis; `before` pages back from the oldest record
	/// the page holds. `app`, `server`, `finding` (or `dropped`), `fault`,
	/// `peer` and `number` narrow the rest ([RecordedCalls.Filter]); `number`
	/// needs a grant with content. Each source adds `recorded`, the `verdict`,
	/// `tracedAt` and, with content, the call's `parties`. Only the calls the
	/// viewer's grants show are read ([TraceAccess]). See [RecordedCalls].
	@GET
	@javax.ws.rs.Path("/recorded")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Read calls the flight recorder wrote out, from the analytics database.")
	public Response recorded(@QueryParam("call") String call, @QueryParam("since") Long since,
			@QueryParam("before") Long before, @QueryParam("app") String app, @QueryParam("server") String server,
			@QueryParam("finding") String finding, @QueryParam("fault") String fault,
			@QueryParam("peer") String peer, @QueryParam("number") String number,
			@QueryParam("limit") Integer limit) {
		TraceAccess viewer = viewer();
		if (!isBlank(number) && !viewer.seesContent()) {
			return Response.status(Response.Status.FORBIDDEN).type(MediaType.TEXT_PLAIN)
					.entity("Searching by number needs phi:transcript for every call you see, in Trace's access policy")
					.build();
		}
		String vorpalId = isBlank(call) ? null : call.trim();
		if (vorpalId != null && !vorpalId.matches("[0-9A-Fa-f]{1,16}")) {
			return bad("'call' is a Vorpal-ID: hexadecimal digits");
		}
		if (vorpalId != null) {
			accessed("read:trace", "call", vorpalId.toUpperCase(java.util.Locale.ROOT));
		}
		if (!isBlank(finding) && !finding.trim().matches("(?i)silent|timeout|forgot|error|credentials|unanswered|unrelayed|dropped")) {
			return bad("'finding' is one of silent, timeout, forgot, error, credentials, unanswered, unrelayed, dropped");
		}
		if (!isBlank(fault) && !fault.trim().matches("(?i)downstream|application")) {
			return bad("'fault' is downstream or application");
		}
		RecordedCalls.Filter filter = new RecordedCalls.Filter();
		filter.vorpalId = vorpalId;
		filter.since = since;
		filter.before = before;
		filter.app = isBlank(app) ? null : app;
		filter.server = isBlank(server) ? null : server;
		filter.finding = isBlank(finding) ? null : finding;
		filter.fault = isBlank(fault) ? null : fault;
		filter.peer = isBlank(peer) ? null : peer;
		filter.number = isBlank(number) ? null : number;
		filter.access = viewer.seesAllCalls() ? null : viewer;
		CallflowSettings settings = CallflowStartup.settings();
		String ds = (settings != null) ? settings.getAnalyticsDataSource() : "jdbc/BladeAnalytics";
		int max = (limit == null || limit < 1) ? 50 : Math.min(limit, 500);
		try {
			ObjectNode out = RecordedCalls.read(ds, filter, max);
			viewer.apply(out);
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// The calls saved on this host, newest first: `{"calls":[{"id", "savedAt",
	/// "savedBy", "firstMillis", "steps", "apps"}]}`. See [SavedCalls].
	@GET
	@javax.ws.rs.Path("/saved")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List the saved calls.")
	public Response savedCalls() {
		try {
			ObjectNode out = SavedCalls.list(savedDirectory());
			TraceAccess viewer = viewer();
			if (!viewer.seesAllCalls()) {
				ArrayNode shown = mapper.createArrayNode();
				for (JsonNode summary : out.path("calls")) {
					if (viewer.seesAny(summary.get("scopes"))) {
						shown.add(summary);
					}
				}
				out.set("calls", shown);
			}
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// One saved call, in the shape of a trace snapshot: `sources` as
	/// [#traces] returns them, `sourceFiles`, `savedAt`, `savedBy`.
	@GET
	@javax.ws.rs.Path("/saved/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Read one saved call.")
	public Response savedCall(@PathParam("id") String id) {
		String vorpalId = SavedCalls.vorpalId(id);
		if (vorpalId == null) {
			return bad("A saved call is named by its Vorpal-ID: hexadecimal digits");
		}
		try {
			String trace = SavedCalls.read(savedDirectory(), vorpalId);
			if (trace == null) {
				return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN)
						.entity("No saved call " + vorpalId).build();
			}
			TraceAccess viewer = viewer();
			ObjectNode out = (ObjectNode) mapper.readTree(trace);
			viewer.apply(out);
			if (out.path("sources").size() == 0) {
				return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN)
						.entity("No saved call " + vorpalId).build();
			}
			accessed("read:trace", "call", vorpalId);
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Saves call `id` from the page: `{"sources":[...], "sourceFiles":{...}}`.
	/// Only that call's steps are kept. Saving again replaces it.
	@PUT
	@javax.ws.rs.Path("/saved/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Save one captured call on this host.")
	public Response saveCall(@PathParam("id") String id, String body) {
		if (!Operators.is(security)) {
			return Operators.refused("Saving a call");
		}
		String vorpalId = SavedCalls.vorpalId(id);
		if (vorpalId == null) {
			return bad("A saved call is named by its Vorpal-ID: hexadecimal digits");
		}
		try {
			java.security.Principal user = (security == null) ? null : security.getUserPrincipal();
			JsonNode posted = mapper.readTree(body);
			TraceAccess viewer = viewer();
			// Saving keeps message text, so it needs content; and a call already
			// saved is replaced only by someone who sees all of it.
			String existing = SavedCalls.read(savedDirectory(), vorpalId);
			if (!viewer.seesAll(posted) || (existing != null && !viewer.seesAll(mapper.readTree(existing)))) {
				return Response.status(Response.Status.FORBIDDEN).type(MediaType.TEXT_PLAIN)
						.entity("Saving this call needs a grant that shows all of it, content included").build();
			}
			ObjectNode summary = SavedCalls.write(savedDirectory(), vorpalId, posted,
					(user == null) ? null : user.getName(), TraceAccess.scopesOf(posted.path("sources"),
							org.vorpal.blade.framework.v2.config.SettingsManager.getDomainName()));
			accessed("admin:trace", "call", vorpalId);
			return Response.ok(summary.toString()).build();
		} catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
			return bad(e.getMessage());
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Deletes saved call `id`.
	@DELETE
	@javax.ws.rs.Path("/saved/{id}")
	@Operation(summary = "Delete one saved call.")
	public Response deleteCall(@PathParam("id") String id) {
		if (!Operators.is(security)) {
			return Operators.refused("Deleting a saved call");
		}
		String vorpalId = SavedCalls.vorpalId(id);
		if (vorpalId == null) {
			return bad("A saved call is named by its Vorpal-ID: hexadecimal digits");
		}
		try {
			String existing = SavedCalls.read(savedDirectory(), vorpalId);
			if (existing != null && !viewer().seesAll(mapper.readTree(existing))) {
				return Response.status(Response.Status.FORBIDDEN).type(MediaType.TEXT_PLAIN)
						.entity("Deleting this call needs a grant that shows all of it, content included").build();
			}
			if (!SavedCalls.delete(savedDirectory(), vorpalId)) {
				return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN)
						.entity("No saved call " + vorpalId).build();
			}
			accessed("admin:trace", "call", vorpalId);
			return Response.noContent().build();
		} catch (Exception e) {
			return error(e);
		}
	}

	private static java.nio.file.Path savedDirectory() {
		CallflowSettings settings = CallflowStartup.settings();
		return SavedCalls.directory((settings != null) ? settings.getSavedTracesDirectory() : null);
	}

	/// The armed rules as each app on each node sees them:
	/// `{"sources":[{"app","server","enabled","rules":[...]}]}`.
	@GET
	@javax.ws.rs.Path("/rules")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List the armed trace rules across the domain.")
	public Response rules() {
		if (!Operators.is(security)) {
			return Operators.refused("Reading the armed rules");
		}
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(JMX_DOMAIN_RUNTIME);
			ObjectNode out = mapper.createObjectNode();
			ArrayNode arr = out.putArray("sources");
			for (ObjectName on : traceMBeans(mbs)) {
				try {
					JsonNode parsed = mapper.readTree((String) mbs.getAttribute(on, "RulesJson"));
					ObjectNode src = arr.addObject();
					src.put("app", on.getKeyProperty("Name"));
					src.put("server", location(on));
					src.put("enabled", parsed.path("enabled").asBoolean(false));
					src.set("rules", parsed.get("rules"));
				} catch (Exception ex) {
					log.log(Level.FINE, "rules read failed for " + on + ": " + ex.getMessage());
				}
			}
			if (!viewer().seesContent()) {
				// A rule's pattern is often a caller's number.
				for (JsonNode src : arr) {
					for (JsonNode rule : src.path("rules")) {
						if (rule.isObject()) {
							((ObjectNode) rule).put("pattern", "(hidden)");
							((ObjectNode) rule).put("label", "(hidden)");
						}
					}
				}
			}
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// Arm a rule on EVERY app in the domain — one action arms the whole chain.
	@POST
	@javax.ws.rs.Path("/arm")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Arm a trace rule on every app in the domain.")
	public Response arm(@QueryParam("label") String label,
			@QueryParam("attribute") String attribute,
			@QueryParam("pattern") String pattern,
			@QueryParam("maxCaptures") Integer maxCaptures) {
		if (!Operators.is(security)) {
			return Operators.refused("Arming a trace rule");
		}
		if (isBlank(attribute) || isBlank(pattern)) {
			return bad("Missing 'attribute' or 'pattern' parameter");
		}
		try {
			// validate ONCE here, so a typo'd regex is a clean 400 instead of a
			// per-node failure buried in each MBean invoke
			Pattern.compile(pattern);
		} catch (Exception e) {
			return bad("Bad regex pattern: " + e.getMessage());
		}
		int cap = maxCaptures != null ? maxCaptures : 5;
		String ruleLabel = isBlank(label) ? attribute + " ~ " + pattern : label.trim();
		// Arming decides whose calls are captured, message by message.
		accessed("admin:trace", "traceRule", attribute.trim() + " ~ " + pattern);
		return fanOut("arm",
				new Object[] { ruleLabel, attribute.trim(), pattern, cap },
				new String[] { String.class.getName(), String.class.getName(), String.class.getName(), "int" });
	}

	/// Drop all rules everywhere (already-armed calls finish recording).
	@POST
	@javax.ws.rs.Path("/disarm")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Disarm all trace rules on every app in the domain.")
	public Response disarm() {
		if (!Operators.is(security)) {
			return Operators.refused("Disarming trace rules");
		}
		return fanOut("disarm", new Object[0], new String[0]);
	}

	/// Empty every node's step buffer.
	@POST
	@javax.ws.rs.Path("/clear")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Clear the buffered trace steps on every app in the domain.")
	public Response clear() {
		if (!Operators.is(security)) {
			return Operators.refused("Clearing the trace buffers");
		}
		return fanOut("clearSteps", new Object[0], new String[0]);
	}

	/// Invoke one operation on every Trace MBean in the domain; report how many
	/// took it and how many failed (an app mid-redeploy shouldn't abort the rest).
	private Response fanOut(String operation, Object[] args, String[] signature) {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(JMX_DOMAIN_RUNTIME);
			int ok = 0, failed = 0;
			for (ObjectName on : traceMBeans(mbs)) {
				try {
					mbs.invoke(on, operation, args, signature);
					ok++;
				} catch (Exception ex) {
					failed++;
					log.log(Level.FINE, operation + " failed for " + on + ": " + ex.getMessage());
				}
			}
			ObjectNode out = mapper.createObjectNode();
			out.put("operation", operation);
			out.put("ok", ok);
			out.put("failed", failed);
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	private static List<ObjectName> traceMBeans(MBeanServer mbs) throws Exception {
		List<ObjectName> out = new ArrayList<>();
		for (ObjectInstance inst : mbs.queryMBeans(new ObjectName(TRACE_PATTERN), null)) {
			if (inst.getObjectName().getKeyProperty("Name") != null) {
				out.add(inst.getObjectName());
			}
		}
		return out;
	}

	private static String location(ObjectName on) {
		return on.getKeyProperty("Location");
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	private Response bad(String message) {
		return Response.status(Response.Status.BAD_REQUEST).type(MediaType.TEXT_PLAIN).entity(message).build();
	}

	private Response error(Throwable t) {
		log.log(Level.WARNING, "traces API failed", t);
		StringWriter sw = new StringWriter();
		try (PrintWriter pw = new PrintWriter(sw)) {
			t.printStackTrace(pw);
		}
		return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.type(MediaType.TEXT_PLAIN)
				.entity(t.getClass().getName() + ": " + t.getMessage() + "\n\n" + sw).build();
	}
}
