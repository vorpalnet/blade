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
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

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
			return Response.ok(out.toString()).build();
		} catch (Exception e) {
			return error(e);
		}
	}

	/// The armed rules as each app on each node sees them:
	/// `{"sources":[{"app","server","enabled","rules":[...]}]}`.
	@GET
	@javax.ws.rs.Path("/rules")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "List the armed trace rules across the domain.")
	public Response rules() {
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
		return fanOut("disarm", new Object[0], new String[0]);
	}

	/// Empty every node's step buffer.
	@POST
	@javax.ws.rs.Path("/clear")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Clear the buffered trace steps on every app in the domain.")
	public Response clear() {
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
