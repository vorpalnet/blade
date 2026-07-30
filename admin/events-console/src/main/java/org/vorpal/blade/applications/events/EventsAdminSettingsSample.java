package org.vorpal.blade.applications.events;

import org.vorpal.blade.framework.v3.events.EventBus;

/// Shipped defaults for the Events console.
///
/// The event-bus topic is protected out of the box. Purging it is not a local
/// mistake: several applications subscribe, and a purge takes the backlog of
/// every one of them at once — including the analytics sink, whose loss nobody
/// would report, because nothing reads that database yet.
///
/// The retired analytics queue used to be listed here too. It is no longer
/// provisioned and nothing publishes to it, so protecting it would only stop an
/// operator clearing it out.
public class EventsAdminSettingsSample extends EventsAdminSettings {

	private static final long serialVersionUID = 1L;

	public EventsAdminSettingsSample() {
		this.setAllowDestructiveOperations(true);
		this.setProtectedDestinations(EventBus.TOPIC_JNDI);
	}
}
