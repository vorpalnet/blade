package org.vorpal.blade.applications.portal;

/// Default portal configuration. The portal's own `name` / `tagline` / `description` are set so
/// the data shape is consistent with every other admin app — but the
/// portal filters itself out of its own deck, so this card is never
/// actually rendered. It's here for completeness and for any future
/// "portal of portals" use case.
public class PortalSettingsSample extends PortalSettings {
	private static final long serialVersionUID = 1L;

	public PortalSettingsSample() {
		this.about.setName("BLADE Admin Portal")
				.setTagline("Unified Administration")
				.setDescription("Launcher for every admin app deployed on this AdminServer. The deck is built live from JMX — any app that registers a SettingsMXBean shows up automatically.");
	}
}
