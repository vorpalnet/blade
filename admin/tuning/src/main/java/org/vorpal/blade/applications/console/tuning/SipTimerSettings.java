package org.vorpal.blade.applications.console.tuning;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API for SIP protocol timer and behavior settings (`sipserver.xml`).
///
/// These values live in OCCAS's `sipserver.xml`, which WebLogic registers as a
/// `<custom-resource>` whose descriptor bean is
/// `com.bea.wcp.sip.management.descriptor.beans.SipServerBean`. Rather than
/// editing the file directly (which only touches the node the admin app runs
/// on, skips the descriptor validator, and can be silently reverted when the
/// AdminServer re-serializes its in-memory bean), this reads and writes through
/// the JMX edit tree:
///
/// `DomainConfiguration` → `CustomResources[name=sipserver]` → `CustomResource`
/// (the `SipServerBean` MBean) → per-attribute get/set inside a
/// `startEdit`/`save`/`activate` session.
///
/// Going through `activate` is what persists `sipserver.xml` on the AdminServer
/// and lets every engine node pick up the change when it next restarts. Note
/// the SIP timers are read into a `static` initializer in the engine
/// (`Transaction.<clinit>` reads `T1/T2/T4/TimerB/F` once at class load), so a
/// timer change does NOT take effect on a running engine — it requires a
/// (rolling) restart. The API reports this via `requiresRestart` so the UI can
/// say so plainly.
@Path("/sip-timers")
@Tag(name = "SIP Timers", description = "SIP protocol timeout and behavior settings")
public class SipTimerSettings {

	private static final ObjectMapper mapper = new ObjectMapper();

	/// SipServerBean attribute value types — drives JMX get/set coercion.
	private enum T { LONG, INT, BOOL, STRING }

	/// Maps the JSON field the form sends/receives to the SipServerBean MBean
	/// attribute name and its type. Attribute names and types were taken from
	/// `SipServerBean` (wlss-descriptor-binding.jar): timers are `long`,
	/// `RetryAfterValue` is a `String`, etc.
	private static final class Field {
		final String json;
		final String attr;
		final T type;

		Field(String json, String attr, T type) {
			this.json = json;
			this.attr = attr;
			this.type = type;
		}
	}

	private static final Field[] FIELDS = {
			// Timers (long milliseconds)
			new Field("t1", "T1TimeoutInterval", T.LONG),
			new Field("t2", "T2TimeoutInterval", T.LONG),
			new Field("t4", "T4TimeoutInterval", T.LONG),
			new Field("timerB", "TimerBTimeoutInterval", T.LONG),
			new Field("timerF", "TimerFTimeoutInterval", T.LONG),
			new Field("timerL", "TimerLTimeoutInterval", T.LONG),
			new Field("timerM", "TimerMTimeoutInterval", T.LONG),
			new Field("timerN", "TimerNTimeoutInterval", T.LONG),
			// Behavior
			new Field("defaultBehavior", "DefaultBehavior", T.STRING),
			new Field("staleSessionHandling", "StaleSessionHandling", T.STRING),
			new Field("retryAfterValue", "RetryAfterValue", T.STRING),
			new Field("maxAppSessionLifetime", "MaxApplicationSessionLifetime", T.INT),
			new Field("enableLocalDispatch", "EnableLocalDispatch", T.BOOL),
			new Field("enableSipOutbound", "EnableSipOutBound", T.BOOL),
			new Field("enableDnsSrvLookup", "EnableDnsSrvLookup", T.BOOL),
			new Field("enableTimerAffinity", "EnableTimerAffinity", T.BOOL),
			new Field("enableRPort", "EnableRport", T.BOOL),
			new Field("engineCallStateCache", "EngineCallStateCacheEnabled", T.BOOL),
			new Field("useHeaderForm", "UseHeaderForm", T.STRING),
			new Field("serverHeader", "ServerHeader", T.STRING),
			new Field("enableSend100ForNonInvite", "EnableSend100ForNonInviteTransaction", T.BOOL),
	};

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get SIP timer and protocol settings")
	public Response get() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			// DomainRuntimeServiceMBean.DomainConfiguration → SipServerBean.
			// Memory: [[wls-domain-jmx-bootstrap]].
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			ObjectName sipServer = resolveSipServerBean(mbs, domainConfig);
			if (sipServer == null) {
				return Response.serverError()
						.entity("{\"error\":\"sipserver custom resource not found\"}").build();
			}

			ObjectNode result = mapper.createObjectNode();
			for (Field f : FIELDS) {
				readInto(mbs, sipServer, f, result);
			}
			result.set("overload", readOverload(mbs, sipServer));
			result.put("requiresRestart", true);
			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update SIP timer and protocol settings")
	public Response update(String body) {
		try (CloseableContext ctx = new CloseableContext()) {
			ObjectNode input = (ObjectNode) mapper.readTree(body);

			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName editConfigManager = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(editConfigManager, "startEdit",
					new Object[]{0, 120000}, new String[]{"int", "int"});

			try {
				// Editable Domain via EditServiceMBean.DomainConfiguration, same
				// shape as the WorkManager write path. Memory: [[wls-domain-jmx-bootstrap]].
				ObjectName editService = new ObjectName(
						"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
				ObjectName domainConfig = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");
				ObjectName sipServer = resolveSipServerBean(editMbs, domainConfig);
				if (sipServer == null) {
					throw new IllegalStateException("sipserver custom resource not found");
				}

				for (Field f : FIELDS) {
					setIfPresent(editMbs, sipServer, f, input);
				}
				writeOverload(editMbs, sipServer, input);

				// activate persists sipserver.xml and distributes it; the
				// validator runs here, so an invalid combination is rejected
				// rather than silently written.
				editMbs.invoke(editConfigManager, "save", null, null);
				editMbs.invoke(editConfigManager, "activate",
						new Object[]{120000L}, new String[]{"long"});

				return Response.ok("{\"success\":true,\"requiresRestart\":true}").build();

			} catch (Exception e) {
				editMbs.invoke(editConfigManager, "undoUnactivatedChanges", null, null);
				editMbs.invoke(editConfigManager, "stopEdit", null, null);
				throw e;
			}

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	/// Navigate `DomainConfiguration` → the `sipserver` custom resource → its
	/// `SipServerBean` descriptor bean. Works on both the domainRuntime (read)
	/// and edit (write) MBean servers since both expose the same structure.
	private ObjectName resolveSipServerBean(MBeanServer mbs, ObjectName domainConfig) throws Exception {
		ObjectName[] customResources = (ObjectName[]) mbs.getAttribute(domainConfig, "CustomResources");
		if (customResources == null) {
			return null;
		}
		for (ObjectName cr : customResources) {
			if ("sipserver".equals(mbs.getAttribute(cr, "Name"))) {
				return (ObjectName) mbs.getAttribute(cr, "CustomResource");
			}
		}
		return null;
	}

	/// Read the classic `<overload>` element (`OverloadBean`: threshold-policy /
	/// threshold-value / release-value). `configured=false` means the element is
	/// absent — OCCAS scaffolds an empty `<overload-protection>` (a separate,
	/// richer bean) by default, so most domains will report not-configured here
	/// until an operator sets a policy.
	private ObjectNode readOverload(MBeanServer mbs, ObjectName sipServer) {
		ObjectNode overload = mapper.createObjectNode();
		try {
			ObjectName ovl = (ObjectName) mbs.getAttribute(sipServer, "Overload");
			if (ovl == null) {
				overload.put("configured", false);
				return overload;
			}
			overload.put("configured", true);
			Object policy = mbs.getAttribute(ovl, "ThresholdPolicy");
			overload.put("thresholdPolicy", policy != null ? policy.toString() : "");
			overload.put("thresholdValue", attrLong(mbs, ovl, "ThresholdValue"));
			overload.put("releaseValue", attrLong(mbs, ovl, "ReleaseValue"));
		} catch (Exception e) {
			overload.put("configured", false);
		}
		return overload;
	}

	/// Write the `<overload>` element, creating it if absent. Only fields present
	/// in the request are set.
	private void writeOverload(MBeanServer editMbs, ObjectName sipServer, ObjectNode input) throws Exception {
		if (!input.has("overload") || !input.get("overload").isObject()) {
			return;
		}
		ObjectNode in = (ObjectNode) input.get("overload");
		ObjectName ovl = (ObjectName) editMbs.getAttribute(sipServer, "Overload");
		if (ovl == null) {
			editMbs.invoke(sipServer, "createOverload", new Object[]{}, new String[]{});
			ovl = (ObjectName) editMbs.getAttribute(sipServer, "Overload");
		}
		if (ovl == null) {
			return;
		}
		if (present(in, "thresholdPolicy")) {
			editMbs.setAttribute(ovl, new Attribute("ThresholdPolicy", in.get("thresholdPolicy").asText()));
		}
		if (present(in, "thresholdValue")) {
			editMbs.setAttribute(ovl, new Attribute("ThresholdValue", in.get("thresholdValue").asLong()));
		}
		if (present(in, "releaseValue")) {
			editMbs.setAttribute(ovl, new Attribute("ReleaseValue", in.get("releaseValue").asLong()));
		}
	}

	private long attrLong(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object val = mbs.getAttribute(on, name);
			return val != null ? ((Number) val).longValue() : 0L;
		} catch (Exception e) {
			return 0L;
		}
	}

	private boolean present(ObjectNode node, String field) {
		return node.has(field) && !node.get(field).isNull();
	}

	private void readInto(MBeanServer mbs, ObjectName bean, Field f, ObjectNode out) {
		try {
			Object val = mbs.getAttribute(bean, f.attr);
			switch (f.type) {
			case LONG:
				out.put(f.json, val != null ? ((Number) val).longValue() : 0L);
				break;
			case INT:
				out.put(f.json, val != null ? ((Number) val).intValue() : 0);
				break;
			case BOOL:
				out.put(f.json, val != null && (Boolean) val);
				break;
			case STRING:
				out.put(f.json, val != null ? val.toString() : "");
				break;
			}
		} catch (Exception e) {
			// Attribute absent on this OCCAS version — omit it rather than fail
			// the whole read.
		}
	}

	private void setIfPresent(MBeanServer mbs, ObjectName bean, Field f, ObjectNode input) throws Exception {
		if (!input.has(f.json) || input.get(f.json).isNull()) {
			return;
		}
		JsonNode v = input.get(f.json);
		Object value;
		switch (f.type) {
		case LONG:
			value = v.asLong();
			break;
		case INT:
			value = v.asInt();
			break;
		case BOOL:
			value = v.asBoolean();
			break;
		case STRING:
		default:
			value = v.asText();
			break;
		}
		mbs.setAttribute(bean, new Attribute(f.attr, value));
	}

	private Response errorResponse(Exception e) {
		String msg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown error";
		return Response.serverError().entity("{\"error\":\"" + msg + "\"}").build();
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
