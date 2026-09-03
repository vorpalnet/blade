package org.vorpal.blade.services.proxy.block.api;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the proxy-block config API.
///
/// Left empty: with no `getClasses()`/`getSingletons()` the container scans for
/// `@Path` classes — here, [LoadConfig]. The live base is
/// `/proxy-block/api/v1` (WAR context-root `proxy-block` + this `/api` + the
/// resource's `@Path("v1")`).
///
/// ## Why this class exists
///
/// Without an `@ApplicationPath` the container serves JAX-RS resources under
/// its own default base, `/resources`, so [LoadConfig] answered at
/// `/proxy-block/resources/v1/config/load/{id}` while `web.xml` constrained
/// `/resources/api/*`, `/api/*` and `/v1/*`. None of those match, so the
/// endpoint was unauthenticated. It returns a synthesized sample today, so
/// nothing customer-owned leaked; the descriptor was still wrong, and the same
/// mistake on the app it is a template for would not be harmless.
///
/// The base is `/api` rather than `/api/v1` because the resource already
/// carries the version in its own `@Path("v1")`.
@ApplicationPath("/api")
public class RestApplication extends Application {
}
