package org.vorpal.blade.services.listener;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the call listener.
public class ListenerSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String driverName;
	private Map<String, String> driverProperties = new LinkedHashMap<>();
	private boolean record = true;
	private boolean transcribe = true;
	private boolean publishUtterances = true;
	private LinkedList<String> scoreVoices = new LinkedList<>();
	private LinkedList<String> recordAttributes = new LinkedList<>();
	private LinkedList<String> transcribeHints = new LinkedList<>();
	private LinkedList<String> transcribeHintAttributes = new LinkedList<>();
	private boolean redact = true;
	private Map<String, String> redactPatterns = new LinkedHashMap<>();

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

	@JsonPropertyDescription("Record the calls routed through this application. False still anchors the call when "
			+ "anything else here needs its audio, and records nothing. Turning recording off does not "
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

	@JsonPropertyDescription("Publish every utterance on the event bus as it is first decoded, as a Call Utterance "
			+ "event with the text and the party (caller or callee). This is what a live screen shows. Works "
			+ "whether or not the call is recorded, and stores nothing: the stored transcript is the transcribe "
			+ "setting's.")
	public boolean isPublishUtterances() {
		return publishUtterances;
	}

	public void setPublishUtterances(boolean publishUtterances) {
		this.publishUtterances = publishUtterances;
	}

	@JsonPropertyDescription("The parties whose voices are scored for being synthetic, from caller and callee. Each "
			+ "scored window is published on the event bus as a Call Voice Assessed event. Scoring is inference on "
			+ "the media server, so name only the parties that need it; an agent's own voice rarely does. Empty "
			+ "scores nobody.")
	public LinkedList<String> getScoreVoices() {
		return scoreVoices;
	}

	public void setScoreVoices(LinkedList<String> scoreVoices) {
		this.scoreVoices = scoreVoices;
	}

	/// Whether anything configured here needs the call's audio: when not, the
	/// call passes through unanchored. An installed [org.vorpal.blade.framework.v3.media.CallAnalyzer]
	/// needs it too, which the servlet checks separately.
	boolean needsAudio() {
		return record || publishUtterances || (scoreVoices != null && !scoreVoices.isEmpty());
	}

	@JsonPropertyDescription("Names and identifiers every call is likely to contain, such as the company's name or the "
			+ "agents' names, in their natural casing. The transcriber leans its recognizer toward them and the "
			+ "framework corrects a decode that sounds like one of them, keeping what was heard beside the "
			+ "correction. Per-call phrases come from transcribeHintAttributes.")
	public LinkedList<String> getTranscribeHints() {
		return transcribeHints;
	}

	public void setTranscribeHints(LinkedList<String> transcribeHints) {
		this.transcribeHints = transcribeHints;
	}

	@JsonPropertyDescription("Session attribute names whose values are phrases this particular call is likely to "
			+ "contain: a caller's name or member ID that a Selector looked up from the signaling. Read when the "
			+ "conversation starts and used the same way as transcribeHints.")
	public LinkedList<String> getTranscribeHintAttributes() {
		return transcribeHintAttributes;
	}

	public void setTranscribeHintAttributes(LinkedList<String> transcribeHintAttributes) {
		this.transcribeHintAttributes = transcribeHintAttributes;
	}

	@JsonPropertyDescription("Find protected values in each utterance as it is transcribed and store a redacted "
			+ "rendition beside the verbatim text: card numbers, social security numbers, phone numbers, "
			+ "account and member identifiers, numeric dates. The verbatim text is still stored; which of "
			+ "the two a reviewer sees is decided when they read it, by the phi:unredact permission. False "
			+ "stores verbatim text only and marks the transcript VERBATIM.")
	public boolean isRedact() {
		return redact;
	}

	public void setRedact(boolean redact) {
		this.redact = redact;
	}

	@JsonPropertyDescription("What to redact, as kind -> regular expression, for example memberId -> "
			+ "[A-Z]{2}\\d{6}. Empty means the built-in set (card with a checksum test, ssn, phone, number, "
			+ "date). A kind named like a built-in one replaces it; a kind mapped to an empty expression "
			+ "removes it. Matched against the text the recognizer produced, so a value spoken as digits "
			+ "is matched as digits.")
	public Map<String, String> getRedactPatterns() {
		return redactPatterns;
	}

	public void setRedactPatterns(Map<String, String> redactPatterns) {
		this.redactPatterns = (redactPatterns == null) ? new LinkedHashMap<>() : redactPatterns;
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
