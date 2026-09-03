package org.vorpal.blade.services.context;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the context API.
///
/// Left empty: with no `getClasses()`/`getSingletons()` the container scans for
/// `@Path` classes — here, [ContextRestAPI]. The live base is
/// `/context/api/v1` (WAR context-root `context` + this `/api` + the
/// resource's `@Path("v1")`).
///
/// ## Why this class exists
///
/// Without an `@ApplicationPath` the container serves JAX-RS resources under
/// its own default base, `/resources`, so [ContextRestAPI] answered at
/// `/context/resources/v1/{key}`. `web.xml` constrained `/resources/api/*`,
/// `/api/*` and `/v1/*` — none of which match that — so every method on this
/// API was served with no authentication, including the three that mutate a
/// live call's headers.
///
/// Declaring the base here is what lets `web.xml` name a pattern that provably
/// covers the API: one `@ApplicationPath`, one `<url-pattern>`, the same
/// prefix. `services/events` establishes the shape. A `@Path` class in an app
/// with no `Application` subclass is the bug, not a shortcut.
///
/// The base is `/api` rather than `/api/v1` because the resource already
/// carries the version in its own `@Path("v1")`; naming it in both places
/// would serve the API at `/api/v1/v1`.
@ApplicationPath("/api")
public class RestApplication extends Application {
}
