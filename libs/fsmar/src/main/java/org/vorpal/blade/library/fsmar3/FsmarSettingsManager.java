package org.vorpal.blade.library.fsmar3;

import java.io.IOException;

import javax.servlet.ServletException;

import org.vorpal.blade.framework.v2.config.Settings;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.fsmar.AppRouterConfiguration;

/// [SettingsManager] that loads the FSMAR config through [FsmarSettings] — the
/// generic `fsmar.json` with a `fsmar2.json` legacy fallback and auto-upgrade of
/// a pre-3 config on load. Created by the FSMAR [AppRouter] in place of the base
/// SettingsManager; the only override is the `createSettings` factory seam.
public class FsmarSettingsManager extends SettingsManager<AppRouterConfiguration> {

	public FsmarSettingsManager(String name, Class<AppRouterConfiguration> clazz,
			AppRouterConfiguration sample) throws ServletException, IOException {
		super(name, clazz, sample);
	}

	@Override
	protected Settings<AppRouterConfiguration> createSettings(String name) {
		return new FsmarSettings(clazz, this, name, mapper, sample);
	}
}
