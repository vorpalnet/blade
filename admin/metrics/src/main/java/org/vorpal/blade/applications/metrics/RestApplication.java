package org.vorpal.blade.applications.metrics;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS root. `/api/v1`, not `/`, or JAX-RS swallows the static assets.
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
