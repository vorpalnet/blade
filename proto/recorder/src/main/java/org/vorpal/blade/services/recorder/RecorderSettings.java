package org.vorpal.blade.services.recorder;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the call recorder.
public class RecorderSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String driverName;
	private Map<String, String> driverProperties = new LinkedHashMap<>();
	private boolean record = true;
	private boolean transcribe = true;
	private LinkedList<String> recordAttributes = new LinkedList<>();

	@JsonPropertyDescription("JSR-309 driver name to obtain the media-server factory from (the registered "
			+ "Driver SPI). Leave blank to use the single registered driver.")
	public String getDriverName() {
		return driverName;
	}

	public void setDriverName(String driverName) {
		this.driverName = driverName;
	}

	@JsonPropertyDescription("Properties handed to the JSR-309 driver when the media-server factory is created.")
	public Map<String, String> getDriverProperties() {
		return driverProperties;
	}

	public void setDriverProperties(Map<String, String> driverProperties) {
		this.driverProperties = driverProperties;
	}

	@JsonPropertyDescription("Record the calls routed through this application. False leaves the application "
			+ "in the path, still passing calls through, recording nothing. Turning recording off does not "
			+ "delete what was already recorded, and the retention rule on the store means it cannot.")
	public boolean isRecord() {
		return record;
	}

	public void setRecord(boolean record) {
		this.record = record;
	}

	@JsonPropertyDescription("Transcribe each recorded conversation as it runs, one stored object per utterance "
			+ "beside the recording, with each party heard separately so the speaker is known. Needs a driver "
			+ "that can transcribe and a transcript archive on the classpath; with either missing the "
			+ "conversation is recorded without a transcript and the log says so. The transcript pauses with "
			+ "the recorder, so a passage kept out of the audio is not written down either.")
	public boolean isTranscribe() {
		return transcribe;
	}

	public void setTranscribe(boolean transcribe) {
		this.transcribe = transcribe;
	}

	@JsonPropertyDescription("Session attribute names to carry onto each recording as the attributes an access "
			+ "rule matches on, such as department or queue. A Selector writes the value into session state "
			+ "during the call; these are copied onto the recording before any audio, because session state "
			+ "dies with the call and the access decision happens later. Every recording also carries a 'call' "
			+ "attribute naming the call it belongs to, so the conversations of one transferred call can be "
			+ "found together. Empty means recordings carry no attributes, so only a rule with an empty match "
			+ "will reach them.")
	public LinkedList<String> getRecordAttributes() {
		return recordAttributes;
	}

	public void setRecordAttributes(LinkedList<String> recordAttributes) {
		this.recordAttributes = recordAttributes;
	}
}
