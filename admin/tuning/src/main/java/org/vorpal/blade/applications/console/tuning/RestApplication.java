package org.vorpal.blade.applications.console.tuning;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the BLADE Tuning admin tool.
///
/// `@ApplicationPath` is `/api/v1` (NOT `/`). A root path would make JAX-RS
/// catch every request to the WAR — including static assets like `index.html`
/// and `favicon.svg` — and serve a "Not Found" page for any path that
/// doesn't match a `@Path`-annotated resource. Resources keep their own
/// short paths (e.g. `@Path("/jvm")`); externally URLs remain
/// `/blade/tuning/api/v1/jvm`, `/blade/tuning/api/v1/cluster`, etc.
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
