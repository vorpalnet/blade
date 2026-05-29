/// Captures raw inbound SIP headers on initial requests so external apps can
/// retrieve them via the REST API after a cloud-provider trunk has scrubbed or
/// rewritten them.
///
/// Each call's headers are stored on its `SipApplicationSession`, indexed by
/// configurable Selectors (e.g. by `Call-ID` or an app-assigned correlator),
/// and exposed for lookup and mutation over a JAX-RS API while the call is in
/// progress.
package org.vorpal.blade.services.context;
