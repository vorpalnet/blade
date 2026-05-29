package org.vorpal.blade.applications.api;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS rooted at `/api/v1` (NOT `/`). A root path would make JAX-RS swallow
/// every request and break static-asset serving (index.html, the Scalar
/// bundle). See memory `[[jaxrs-application-path]]`.
@ApplicationPath("/api/v1")
public class ApiRestApplication extends Application {
}
