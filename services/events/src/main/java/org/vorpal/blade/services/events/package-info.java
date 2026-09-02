/// The engine-tier runtime of the BLADE event bus: the HTTP→JMS ingress, the
/// catalog that says what an event is, and the publishers that put events on
/// their destinations.
///
/// **What runs here.**
/// [org.vorpal.blade.services.events.EventBusStartup] hangs the lifecycle off a
/// `@WebListener` (this app has no SIP servlet), and
/// [org.vorpal.blade.services.events.EventCatalogSettingsManager] does the work:
/// it registers the catalog with the framework and installs one
/// `EventPublisher` per destination the catalog names, re-diffing on every
/// config reload so a change published from the console lands without a
/// redeploy.
/// [org.vorpal.blade.services.events.EventIngestResource] accepts CloudEvents
/// over HTTP at `POST /events/api/v1/events`, and
/// [org.vorpal.blade.services.events.EventValidator] checks each payload against
/// the schema its type declares.
///
/// **What does not run here.** The designer, the JMS administration console and
/// the live inspector are admin-tier and live in `admin/events-console`, on the
/// AdminServer. The model and the code generator are framework-side, in
/// [org.vorpal.blade.framework.v3.events], because both tiers need them and the
/// framework jar is the only one a skinny WAR carries.
///
/// **Why the ingress exists at all.** A BLADE app can publish natively through
/// `EventBus`. This endpoint is for producers that are not BLADE apps — a
/// voice-attendant sidecar in Python, for instance, already emits exactly this
/// envelope, so pointing its sink here needs no producer change whatsoever.
package org.vorpal.blade.services.events;
