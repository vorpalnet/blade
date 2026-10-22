package org.vorpal.blade.applications.portal;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.vorpal.blade.framework.v3.configuration.AppCards;

/// The application list ([AppCards]) for the portal's own pages, the printable
/// Deployment Inventory and the service page, behind the container's login.
/// The dashboard serves the same list behind its own sign-in.
@Path("/v1/cards")
public class PortalCardsResource {

	private static final Logger log = Logger.getLogger(PortalCardsResource.class.getName());

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response list() {
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			return Response.ok(AppCards.json(mbs)).type(MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			log.log(Level.WARNING, "PortalCardsResource: JMX walk failed", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.type(MediaType.TEXT_PLAIN)
					.entity("cards lookup failed: " + e.getClass().getSimpleName()
							+ ": " + (e.getMessage() != null ? e.getMessage() : ""))
					.build();
		}
	}
}
