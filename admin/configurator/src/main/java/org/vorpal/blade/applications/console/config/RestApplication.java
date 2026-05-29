package org.vorpal.blade.applications.console.config;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the BLADE Configurator.
///
/// `@ApplicationPath` is `/api/v1` (NOT `/`). A root path would make JAX-RS
/// catch every request to the WAR — including static assets like `index.html`
/// and the login page — and serve a "Not Found" page for any path that
/// doesn't match a `@Path`-annotated resource. Resources (e.g. [ValidationAPI])
/// are rooted at `/`, so externally URLs remain `/configurator/api/v1/validate`,
/// `/configurator/api/v1/deploy`, etc.
///
/// Without this class nothing maps the JAX-RS resources and every `api/v1/*`
/// request returns 404 — which is why `blade-validate.sh` was unreachable.
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
