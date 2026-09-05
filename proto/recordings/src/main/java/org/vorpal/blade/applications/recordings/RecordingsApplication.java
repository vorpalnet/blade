package org.vorpal.blade.applications.recordings;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the review API.
///
/// Declared so `web.xml` can name a pattern that provably covers it. An app with
/// `@Path` classes and no `Application` subclass is served under the container's
/// default `/resources` base, which is how `services/context` ended up serving
/// its whole API unauthenticated while its descriptor named paths nothing
/// answered on.
@ApplicationPath("/api/v1")
public class RecordingsApplication extends Application {
}
