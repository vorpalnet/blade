package org.vorpal.blade.framework.v3.events;

import java.util.Collection;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// The record of configuration changes: who saved which file, and where it went.
///
/// A configuration file decides how every call is routed, so a change to one is
/// the change most worth being able to name afterwards. Two events, because two
/// different things happen: a person saves a file in an editor
/// ([BladeEventTypes#CONFIG_SAVED]), and the Configurator pushes a changed file
/// to the servers that run its application ([BladeEventTypes#CONFIG_PUBLISHED]).
/// The second is published for every change it sees, including one made by
/// hand on the AdminServer, which no editor could have recorded.
///
/// Both run on the AdminServer, where the bus's topic is not bound; the
/// framework finds it on the running engines by itself.
public final class ConfigEvents {

	private ConfigEvents() {
	}

	/// @param actor  who changed it, as the container authenticated them
	/// @param file   the file, relative to the configuration directory
	/// @param editor which editor: `configurator`, `flow`, `files`
	/// @param change `saved`, `restored` (an earlier version put back) or
	///               `deleted`
	public static void saved(String actor, String file, String editor, String change) {
		Events.publish(BladeEventTypes.CONFIG_SAVED, file, data -> data
				.put("actor", (actor == null) ? "unknown" : actor)
				.put("file", file)
				.put("editor", editor)
				.put("change", change)
				.put("node", SettingsManager.getServerName()));
	}

	/// @param file        the file, relative to the configuration directory
	/// @param application the application it configures
	/// @param scope       `domain`, `cluster` or `server`
	/// @param pushedTo    servers that reloaded it
	/// @param failed      servers that did not
	public static void published(String file, String application, String scope, Collection<String> pushedTo,
			Collection<String> failed) {
		Events.publish(BladeEventTypes.CONFIG_PUBLISHED, file, data -> {
			data.put("file", file).put("application", application).put("scope", scope)
					.put("node", SettingsManager.getServerName());
			pushedTo.forEach(data.putArray("pushedTo")::add);
			if (!failed.isEmpty()) {
				failed.forEach(data.putArray("failed")::add);
			}
		});
	}
}
