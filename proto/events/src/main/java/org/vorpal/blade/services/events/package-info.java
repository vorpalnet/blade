/// The BLADE v3 event bus deployable — the HTTP→JMS ingress plus a reference
/// consumer.
///
/// [org.vorpal.blade.services.events.EventBusStartup] installs the topic
/// publisher at WAR startup; [org.vorpal.blade.services.events.EventIngestResource]
/// accepts CloudEvents over HTTP and republishes them onto the bus (so a non-Java
/// producer like the Gumball attendant publishes with no code change); and
/// [org.vorpal.blade.services.events.CalendarEventListener] is a reference MDB
/// showing a downstream app consuming `meeting.scheduled` events off the topic.
///
/// The reusable core (the CloudEvent envelope, the publisher, the JNDI
/// constants) lives in the framework at
/// [org.vorpal.blade.framework.v3.events]. Provision the JMS resources with
/// `notes/configure-events-jms.py` before deploying.
///
/// Incubating in `proto/` per the repo convention that new apps start there;
/// promote to `services/` once proven.
package org.vorpal.blade.services.events;
