package org.vorpal.blade.services.player;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Sample defaults for the player app: the JSR-309 media controller driver on the local media node, playing a
/// placeholder prompt. Real deployments edit these via the Configurator.
public class PlayerSettingsSample extends PlayerSettings {
	private static final long serialVersionUID = 1L;

	public PlayerSettingsSample() {
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();

		setDriverName("org.vorpal.gryphon.kurento");
		getDriverProperties().put("kurento.ws.url", "ws://localhost:8888/kurento");
		setMediaUri("http://media.example.com/greeting.wav");
		setLoop(false);
		setRecord(false);
		setRecordUri("file:///tmp/kurento/recording.webm");
	}
}
