package org.vorpal.blade.applications.events;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/// JAX-RS root for the Events console.
///
/// `/api/v1` rather than `/`: a root application path makes JAX-RS swallow
/// static assets, so the HTML, CSS and JS of the console itself would stop
/// being served. The Configurator's `RestApplication` documents the same
/// hard-won detail.
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
