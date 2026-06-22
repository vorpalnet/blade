package org.vorpal.blade.applications.console.security;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the BLADE Security admin tool.
///
/// `@ApplicationPath` is `/api/v1` (not `/`) so JAX-RS doesn't swallow static
/// assets. Left empty: with no `getClasses()`/`getSingletons()` the container
/// scans for `@Path`/`@Provider` classes — including
/// [org.vorpal.blade.framework.v3.security.JwtAuthFilter], which ships in the
/// framework jar bundled in `WEB-INF/lib`. The filter activates here because
/// this app publishes a JWT config supplier (see [SecuritySettingsStartup]);
/// in apps that don't, it no-ops.
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
