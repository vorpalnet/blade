package org.vorpal.blade.services.player;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// Configuration for the player/recorder app.
///
/// The app is **vendor-neutral JSR-309**: it names a 309 driver and hands it a bag of driver
/// properties, but knows nothing about which media server sits behind it. In a typical deployment
/// the one property that matters is the media server's WebSocket URL, but this app runs unchanged
/// against any registered 309 driver.
@SchemaAbout(
		name = "Player",
		tagline = "Media Player / Recorder",
		description = "Answers an inbound call, anchors its media on a JSR-309 media server, and plays "
				+ "a prompt or music to the caller (optionally recording the caller's audio). "
				+ "Vendor-neutral: the media server is reached through whichever 309 driver is named.")
public class PlayerSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String driverName;
	private Map<String, String> driverProperties = new LinkedHashMap<>();
	private String mediaUri;
	private boolean loop = false;
	private boolean record = false;
	private String recordUri;
	private boolean conference = false;

	@JsonPropertyDescription("JSR-309 driver name to obtain the media-server factory from "
			+ "(the registered Driver SPI). Leave blank to use the single registered driver.")
	public String getDriverName() {
		return driverName;
	}

	public void setDriverName(String driverName) {
		this.driverName = driverName;
	}

	@JsonPropertyDescription("Driver-specific factory properties passed verbatim to the 309 driver "
			+ "(e.g. the media server's WebSocket URL).")
	public Map<String, String> getDriverProperties() {
		return driverProperties;
	}

	public void setDriverProperties(Map<String, String> driverProperties) {
		this.driverProperties = driverProperties;
	}

	@JsonPropertyDescription("Media to play to the caller — any URI the media server can fetch "
			+ "(file://, http://, rtsp://). A music file or a pre-rendered TTS prompt.")
	public String getMediaUri() {
		return mediaUri;
	}

	public void setMediaUri(String mediaUri) {
		this.mediaUri = mediaUri;
	}

	@JsonPropertyDescription("Replay the media when it finishes (music-on-answer). When false, the "
			+ "call is released after one play.")
	public boolean isLoop() {
		return loop;
	}

	public void setLoop(boolean loop) {
		this.loop = loop;
	}

	@JsonPropertyDescription("Record the caller's audio while the prompt plays.")
	public boolean isRecord() {
		return record;
	}

	public void setRecord(boolean record) {
		this.record = record;
	}

	@JsonPropertyDescription("Destination URI the caller's audio is recorded to when recording is on "
			+ "(e.g. file:///var/recordings/{id}.webm — {id} becomes the call's session id, so "
			+ "concurrent calls record to separate files; braces only, ${...} is reserved by the config layer).")
	public String getRecordUri() {
		return recordUri;
	}

	public void setRecordUri(String recordUri) {
		this.recordUri = recordUri;
	}

	@JsonPropertyDescription("Conference mode: callers to the same dialed user (the To user part) share "
			+ "one audio mix on the media server instead of each hearing the prompt. The room opens "
			+ "with the first caller and closes when the last one leaves. mediaUri/loop/record do not "
			+ "apply in this mode.")
	public boolean isConference() {
		return conference;
	}

	public void setConference(boolean conference) {
		this.conference = conference;
	}
}
