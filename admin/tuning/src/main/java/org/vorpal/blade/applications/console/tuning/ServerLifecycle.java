package org.vorpal.blade.applications.console.tuning;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/// REST API to restart a server through Node Manager.
///
/// **Engines**: `forceShutdown()` → poll `State` until `SHUTDOWN` → `start()`,
/// orchestrated on a background thread so the POST returns immediately. The
/// `start()` is carried out by Node Manager, so the target must be NM-managed.
///
/// **AdminServer**: nobody is left to call `start()` once this JVM exits, so the
/// restart leans on Node Manager's auto-restart the way a wifi router leans on
/// its watchdog: reply to the HTTP request, then `halt(1)` a moment later. The
/// abrupt exit reads as a server failure to Node Manager, which restarts any
/// server it started. If the AdminServer was started by hand instead of via
/// Node Manager (blade.sh's boot service uses nmStart), this endpoint is a
/// remote power-off — the UI's confirm dialog says so.
@Path("/servers")
@Tag(name = "Servers", description = "Server lifecycle: restart via Node Manager")
public class ServerLifecycle {

	@POST
	@Path("/{name}/restart")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Restart a server (engines: force-shutdown + start; AdminServer: exit and rely on Node Manager auto-restart)")
	public Response restart(@PathParam("name") String name) {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");

			String adminName = System.getProperty("weblogic.Name", "AdminServer");
			try {
				ObjectName domainConfig = (ObjectName) dr.getAttribute(service, "DomainConfiguration");
				Object an = dr.getAttribute(domainConfig, "AdminServerName");
				if (an != null && !an.toString().isEmpty()) adminName = an.toString();
			} catch (Exception ignore) {
			}

			if (name.equals(adminName)) {
				// Reply first, then die: the grace period lets the response
				// flush before halt(1). halt (not exit) skips graceful shutdown
				// on purpose — a clean NM-protocol shutdown would be recorded
				// as intentional and NOT auto-restarted.
				Thread killer = new Thread(() -> {
					try {
						Thread.sleep(2000L);
					} catch (InterruptedException ignore) {
					}
					Runtime.getRuntime().halt(1);
				}, "tuning-admin-restart");
				killer.setDaemon(true);
				killer.start();
				return Response.ok("{\"success\":true,\"admin\":true,\"countdown\":90}").build();
			}

			ObjectName domainRuntime = (ObjectName) dr.getAttribute(service, "DomainRuntime");
			ObjectName lifecycle = (ObjectName) dr.invoke(domainRuntime, "lookupServerLifeCycleRuntime",
					new Object[] { name }, new String[] { "java.lang.String" });
			if (lifecycle == null) {
				return Response.status(Response.Status.NOT_FOUND)
						.entity("{\"error\":\"No such server: " + name.replace("\"", "\\\"") + "\"}").build();
			}

			// The MBeanServer facade is server-wide, not request-scoped, so the
			// background thread can keep using it after this request ends
			// (java:comp lookups would NOT work from a non-request thread).
			final MBeanServer mbs = dr;
			final ObjectName lc = lifecycle;
			Thread worker = new Thread(() -> {
				try {
					mbs.invoke(lc, "forceShutdown", null, null);
					for (int i = 0; i < 90; i++) {
						Thread.sleep(1000L);
						String state = String.valueOf(mbs.getAttribute(lc, "State"));
						if ("SHUTDOWN".equals(state)) {
							break;
						}
					}
					mbs.invoke(lc, "start", null, null);
				} catch (Exception ignore) {
					// Best effort: the UI polls the state column; a stuck server
					// shows as not coming back rather than as a lost exception.
				}
			}, "tuning-restart-" + name);
			worker.setDaemon(true);
			worker.start();

			return Response.ok("{\"success\":true}").build();

		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown error";
			return Response.serverError().entity("{\"error\":\"" + msg + "\"}").build();
		}
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
