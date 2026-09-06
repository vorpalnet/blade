package org.vorpal.blade.applications.audit;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the audit API.
///
/// The base is declared here rather than left to the container's default, so
/// `web.xml` can name a pattern that provably covers the API. A `@Path` class in
/// an application with no `Application` subclass is served under `/resources`,
/// which is how an API ends up outside every constraint that was written for it.
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
