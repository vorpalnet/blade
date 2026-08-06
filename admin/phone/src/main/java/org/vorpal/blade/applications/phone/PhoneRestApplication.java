package org.vorpal.blade.applications.phone;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS application for the WebRTC Phone.
///
/// `@ApplicationPath` is `/api/v1` (not `/`) so JAX-RS does not swallow the
/// page and its assets. Left empty so the container scans for `@Path` classes,
/// matching every other admin app.
@ApplicationPath("/api/v1")
public class PhoneRestApplication extends Application {
}
