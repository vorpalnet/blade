package org.vorpal.blade.services.events;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the event bus ingress.
///
/// Left empty: with no `getClasses()`/`getSingletons()` the container scans for
/// `@Path` classes — here, [EventIngestResource]. The full ingress URL is
/// `POST /events/api/v1/events` (WAR context-root `events` + this `/api/v1` +
/// the resource's `/events`).
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
