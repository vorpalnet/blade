package org.vorpal.blade.framework.v3.media.manifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// A transcript of a conversation, as the manifest records it.
///
/// The text itself lives in its own object; this says what produced it, what it
/// covers, and how far it can be trusted. Transcripts sit beside tracks rather
/// than inside one because a transcript can be drawn from several tracks at
/// once, and because it is a different artifact with a different permission:
/// `phi:transcript` is not `phi:play`, so an analyst can be given the words
/// without the audio.
///
/// ## Utterances share the audio's clock
///
/// A transcript's offsets are conversation offsets, the same ones the tracks
/// use, so a line of text and the moment it was said are the same number. That
/// is what lets a reviewer jump from a phrase to the audio, and it only holds
/// because the timeline is preserved: a spliced recording would leave every
/// offset after the first pause pointing at the wrong moment.
///
/// ## Attribution is recorded, not assumed
///
/// Per-track transcription knows who spoke because each participant had their
/// own stream. Transcribing a mix has to infer it, and inference is sometimes
/// wrong. [#getAttribution] says which a reader is holding. A conference stored
/// only as a mix gets the weaker kind and a reviewer is entitled to know.
///
/// ## Versions accumulate
///
/// Re-running a better model appends a new entry carrying its own engine and
/// version rather than replacing what a reviewer may already have acted on.
/// Write-once storage would refuse the overwrite in any case, and keeping both
/// is the honest record of what produced a given text.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TranscriptRef {

	/// How speakers were determined.
	public enum Attribution {
		/// Each party had its own stream, so who spoke is known rather than
		/// inferred.
		PER_TRACK,
		/// Drawn from a mix, so speakers were separated by inference and may be
		/// wrong.
		DIARIZED
	}

	/// Whether protected content has been removed from the text.
	public enum Redaction {
		/// Protected spans are marked and their content withheld. What
		/// `phi:unredact` can reveal.
		REDACTED,
		/// Nothing withheld. Should exist only where policy allows it.
		VERBATIM
	}

	private String id;
	private String language;
	private List<String> source = new ArrayList<>();
	private Attribution attribution;
	private String engine;
	private String model;
	private String modelVersion;
	private String createdUtc;
	private Redaction redaction;
	private Double confidence;
	private boolean complete;
	private String object;
	private Integer utterances;

	public TranscriptRef() {
	}

	public TranscriptRef(String id, String language, Attribution attribution) {
		this.id = id;
		this.language = language;
		this.attribution = attribution;
	}

	@JsonPropertyDescription("Transcript identifier, unique within the conversation.")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Language tag, for example en-US.")
	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}

	@JsonPropertyDescription("Track ids this transcript was drawn from.")
	public List<String> getSource() {
		return source;
	}

	public void setSource(List<String> source) {
		this.source = (source == null) ? new ArrayList<>() : source;
	}

	@JsonPropertyDescription("Whether speakers are known from separate tracks or inferred from a mix.")
	public Attribution getAttribution() {
		return attribution;
	}

	public void setAttribution(Attribution attribution) {
		this.attribution = attribution;
	}

	/// What produced this text. Kept because a transcript may be challenged, and
	/// because it is what makes a later re-run comparable rather than merely
	/// different.
	@JsonPropertyDescription("Recognizer that produced this transcript.")
	public String getEngine() {
		return engine;
	}

	public void setEngine(String engine) {
		this.engine = engine;
	}

	@JsonPropertyDescription("Model used.")
	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	@JsonPropertyDescription("Model version, so two runs can be told apart.")
	public String getModelVersion() {
		return modelVersion;
	}

	public void setModelVersion(String modelVersion) {
		this.modelVersion = modelVersion;
	}

	@JsonPropertyDescription("When this transcript was produced, ISO-8601.")
	public String getCreatedUtc() {
		return createdUtc;
	}

	public void setCreatedUtc(String createdUtc) {
		this.createdUtc = createdUtc;
	}

	public Instant created() {
		return (createdUtc == null) ? null : Instant.parse(createdUtc);
	}

	@JsonPropertyDescription("Whether protected content has been withheld from the text.")
	public Redaction getRedaction() {
		return redaction;
	}

	public void setRedaction(Redaction redaction) {
		this.redaction = redaction;
	}

	@JsonPropertyDescription("Mean recognizer confidence, where the engine reports one.")
	public Double getConfidence() {
		return confidence;
	}

	public void setConfidence(Double confidence) {
		this.confidence = confidence;
	}

	/// False when the transcript does not cover the whole conversation, such as
	/// a live transcript finalised early because the node was lost.
	@JsonPropertyDescription("False when this transcript does not cover the whole conversation.")
	public boolean isComplete() {
		return complete;
	}

	public void setComplete(boolean complete) {
		this.complete = complete;
	}

	/// For a transcript written live this is a prefix, not a file: the
	/// utterances sit beneath it one object each, named by sequence, because
	/// write-once storage cannot grow a single object for the length of a call.
	/// See [TranscriptArchive].
	@JsonPropertyDescription("Stored object holding the text, or the prefix holding one object per utterance, relative to the conversation prefix.")
	public String getObject() {
		return object;
	}

	public void setObject(String object) {
		this.object = object;
	}

	/// How many utterances were stored, so a reader can check the archive holds
	/// what the manifest claims, the way segment counts let it check a track.
	/// Absent for a transcript stored as one object.
	@JsonPropertyDescription("Number of utterance objects stored under the prefix, for a transcript written live.")
	public Integer getUtterances() {
		return utterances;
	}

	public void setUtterances(Integer utterances) {
		this.utterances = utterances;
	}
}
