package org.vorpal.blade.services.player;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Sample defaults for the player app: a JSR-309 driver on the local media node, playing a placeholder
/// prompt. The driver name and the `driverProperties` keys are the installed driver's own (see its
/// documentation); a blank driver name selects the sole registered driver. Real deployments edit these
/// via the Configurator.
public class PlayerSettingsSample extends PlayerSettings {
	private static final long serialVersionUID = 1L;

	public PlayerSettingsSample() {
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();

		setDriverName("");
		getDriverProperties().put("media.server.url", "ws://localhost:8888/");
		setMediaUri("http://media.example.com/greeting.wav");
		setLoop(false);
		setRecord(false);
		setRecordUri("file:///tmp/recordings/recording.webm");
		setConference(false);
	}
}
