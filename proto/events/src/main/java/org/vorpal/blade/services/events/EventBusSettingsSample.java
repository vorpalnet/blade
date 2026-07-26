package org.vorpal.blade.services.events;

/// Seed configuration written to `_samples/events.json.SAMPLE` on first
/// registration, and the fallback the Configurator offers when no live config
/// exists yet.
public class EventBusSettingsSample extends EventBusSettings {

	private static final long serialVersionUID = 1L;

	public EventBusSettingsSample() {
		setSource("/blade/events");
	}
}
