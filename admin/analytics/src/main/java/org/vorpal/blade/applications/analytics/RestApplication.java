package org.vorpal.blade.applications.analytics;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS rooted at `/api` (not `/`) so the welcome-file mechanism can serve
/// `index.html` at the context root without the JAX-RS servlet swallowing it
/// first — see memory `[[jaxrs-application-path]]`.
@ApplicationPath("/api")
public class RestApplication extends Application {
}
