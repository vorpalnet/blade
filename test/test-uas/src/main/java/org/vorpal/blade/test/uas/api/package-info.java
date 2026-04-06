/// JAX-RS REST API for runtime configuration of the Test UAS module.
///
/// Provides endpoints for modifying response behavior without redeployment.
/// Changes take effect immediately on the next incoming call, since
/// [TestInvite][org.vorpal.blade.test.uas.callflows.TestInvite] reads
/// the live configuration on every request.
///
/// ## Key Components
///
/// - [TestUasAPI] - JAX-RS resource at `/api/v1/config` with GET and PUT endpoints
///   for reading and updating
///   [TestUasConfig][org.vorpal.blade.test.uas.config.TestUasConfig] at runtime
///
/// ## Endpoints
///
/// | Method | Path | Description |
/// |--------|------|-------------|
/// | `GET` | `/api/v1/config` | Returns the current configuration |
/// | `PUT` | `/api/v1/config` | Replaces entire configuration |
/// | `PUT` | `/api/v1/config/status` | Updates default response status code |
/// | `PUT` | `/api/v1/config/delay` | Updates default response delay |
/// | `PUT` | `/api/v1/config/duration` | Updates default call duration |
/// | `PUT` | `/api/v1/config/errormap` | Replaces the error map |
///
/// All PUT endpoints modify
/// `UasServlet.settingsManager.getCurrent()` directly.
///
/// @see org.vorpal.blade.test.uas.config.TestUasConfig
/// @see org.vorpal.blade.test.uas.UasServlet
package org.vorpal.blade.test.uas.api;
