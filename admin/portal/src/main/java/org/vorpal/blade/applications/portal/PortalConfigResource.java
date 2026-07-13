package org.vorpal.blade.applications.portal;

import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The RUNNING configuration of one BLADE app or service, read from its
/// Configuration MXBean's `CurrentJson` over federated DomainRuntime JMX —
/// the same registrations the deck walks for card metadata. This is what the
/// nodes are actually routing with right now, which can differ from the file
/// on disk until a publish; printable reports (e.g. the portal's iRouter
/// Dial Plan) want the running truth.
///
/// A service registers once per engine node (Cluster-keyed, with a
/// `Location=<server>` key); an admin app registers domain-scoped on the
/// AdminServer. Cluster-keyed registrations win here — for a name that is
/// both an admin tool and a service (e.g. "analytics"), the SERVICE config is
/// the one a routing report is about — and all nodes share one published
/// config, so any node's `CurrentJson` answers.
@Path("/v1/config")
public class PortalConfigResource {

	private static final Logger log = Logger.getLogger(PortalConfigResource.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Path("/{name}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response get(@PathParam("name") String name) {
		// The name lands inside a JMX ObjectName pattern — restrict it to the
		// slug alphabet SettingsManager registers with rather than quoting.
		if (name == null || !name.matches("[A-Za-z0-9._-]{1,128}")) {
			return Response.status(Response.Status.BAD_REQUEST)
					.type(MediaType.TEXT_PLAIN).entity("invalid config name").build();
		}
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName pattern = new ObjectName("vorpal.blade:Name=" + name + ",Type=Configuration,*");

			ObjectName chosen = null;
			boolean chosenClustered = false;
			// Location -> present; TreeMap for a stable, sorted servers list.
			Map<String, Boolean> servers = new TreeMap<>();
			for (ObjectInstance inst : mbs.queryMBeans(pattern, null)) {
				ObjectName on = inst.getObjectName();
				boolean clustered = on.getKeyProperty("Cluster") != null;
				String location = on.getKeyProperty("Location");
				if (clustered && location != null) {
					servers.put(location, Boolean.TRUE);
				}
				if (chosen == null || (clustered && !chosenClustered)) {
					chosen = on;
					chosenClustered = clustered;
				}
			}
			if (chosen == null) {
				return Response.status(Response.Status.NOT_FOUND)
						.type(MediaType.TEXT_PLAIN)
						.entity("no Configuration MBean registered for '" + name + "'").build();
			}

			String json = (String) mbs.getAttribute(chosen, "CurrentJson");
			if (json == null || json.isEmpty()) {
				return Response.status(Response.Status.NOT_FOUND)
						.type(MediaType.TEXT_PLAIN)
						.entity("'" + name + "' exposes no current configuration").build();
			}

			ObjectNode out = mapper.createObjectNode();
			out.put("name", name);
			ArrayNode arr = out.putArray("servers");
			for (String s : servers.keySet()) arr.add(s);
			out.set("config", mapper.readTree(json));
			return Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			log.log(Level.WARNING, "PortalConfigResource: read failed for " + name, e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.type(MediaType.TEXT_PLAIN)
					.entity("config lookup failed: " + e.getClass().getSimpleName()
							+ ": " + (e.getMessage() != null ? e.getMessage() : ""))
					.build();
		}
	}
}
