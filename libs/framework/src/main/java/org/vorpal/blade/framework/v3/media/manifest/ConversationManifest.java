package org.vorpal.blade.framework.v3.media.manifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// What a conversation's recording is, as stored.
///
/// A call is not one recording. Four levels earn their place:
///
/// ```
/// call            the whole customer experience; survives a transfer
///  |- conversation the unit of access control and retention
///     |- track     one audio stream, with a role and a party
///        |- segment what the recorder actually wrote
/// ```
///
/// This is the conversation level, and it is the contract every reader depends
/// on. A stored manifest outlives the code that wrote it, which is why it
/// carries [#getSchema] and why every timestamp is an ISO-8601 string rather
/// than whatever a `Jackson` module happened to be configured to emit.
///
/// ## The manifest is the commit point
///
/// Audio segments are immutable from birth and go straight to the archive. Only
/// the description changes while a call is up, so only the description needs
/// somewhere mutable to live: see [ManifestStore] for the scratchpad and
/// [ManifestArchive] for the write-once landing.
///
/// A conversation exists, for audit purposes, when its manifest lands in the
/// archive. Before that it is in flight. Because the manifest names every
/// expected track and every segment count, a reader can check the archive holds
/// what this claims rather than trusting it.
///
/// ## Why absence has to be explained
///
/// [#getTracksExpected] is the intended track set. A track that is present but
/// empty, a track that failed, and a track deliberately not recorded because a
/// party withheld consent are three different facts, and only the first is
/// visible from the stored objects. [RecordingTrack.State] carries the
/// difference. An auditor asking why a participant is not in a recording needs
/// the answer to be recorded, not inferred.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationManifest {

	/// The format this manifest is written in. Files outlive code, so a reader
	/// years from now needs to know what it is holding without our source.
	public static final String SCHEMA = "blade.recording.manifest/1";

	/// How a recording's timeline relates to the conversation's.
	///
	/// One constant, deliberately. A spliced timeline cannot be turned back into
	/// a faithful one: it discards where the silence was, desynchronises every
	/// transcript offset after it, and removes the evidence that nothing was cut.
	/// A faithful timeline yields a spliced view on demand. The transformation
	/// runs one way, so the format stores the side it can be derived from.
	///
	/// Preserved means the *offsets* stay true. It does not mean silence is
	/// written to storage: a pause is a hole in [RecordingTrack#getSegments] plus
	/// a [MediaGap] that says why, and a player renders the silence. A ten minute
	/// hold costs no stored audio and still lands every later offset correctly.
	///
	/// Adding a second constant means confronting all of that first.
	public enum Timeline {
		PRESERVED
	}

	private String schema = SCHEMA;
	private String conversation;
	private String call;
	private String epochUtc;
	private Long durationMillis;
	private Timeline timeline = Timeline.PRESERVED;
	private String syncSource;
	private Integer syncAccuracyMillis;
	private Map<String, String> attributes = new LinkedHashMap<>();
	private List<String> tracksExpected = new ArrayList<>();
	private List<RecordingTrack> tracks = new ArrayList<>();
	private List<TranscriptRef> transcripts = new ArrayList<>();
	private boolean complete;
	private String incompleteReason;
	private String finalizedUtc;
	private String finalizedBy;

	public ConversationManifest() {
	}

	public ConversationManifest(String conversation, String call, Instant epoch) {
		this.conversation = conversation;
		this.call = call;
		this.epochUtc = (epoch == null) ? null : epoch.toString();
	}

	@JsonPropertyDescription("Manifest format identifier, so a reader can tell what it is holding.")
	public String getSchema() {
		return schema;
	}

	public void setSchema(String schema) {
		this.schema = schema;
	}

	@JsonPropertyDescription("This conversation's identifier; the unit of access control and retention.")
	public String getConversation() {
		return conversation;
	}

	public void setConversation(String conversation) {
		this.conversation = conversation;
	}

	@JsonPropertyDescription("The call this conversation belongs to. Transfers make one call several conversations.")
	public String getCall() {
		return call;
	}

	public void setCall(String call) {
		this.call = call;
	}

	@JsonPropertyDescription("Absolute UTC instant that every offset in this manifest is measured from, ISO-8601.")
	public String getEpochUtc() {
		return epochUtc;
	}

	public void setEpochUtc(String epochUtc) {
		this.epochUtc = epochUtc;
	}

	/// The epoch as an instant, or null when unset.
	public Instant epoch() {
		return (epochUtc == null) ? null : Instant.parse(epochUtc);
	}

	@JsonPropertyDescription("Conversation length in milliseconds, including any gaps.")
	public Long getDurationMillis() {
		return durationMillis;
	}

	public void setDurationMillis(Long durationMillis) {
		this.durationMillis = durationMillis;
	}

	@JsonPropertyDescription("How the recording's timeline relates to the conversation's. Always preserved.")
	public Timeline getTimeline() {
		return timeline;
	}

	public void setTimeline(Timeline timeline) {
		this.timeline = timeline;
	}

	@JsonPropertyDescription("Where track clocks were anchored to UTC, for example rtcp-sr.")
	public String getSyncSource() {
		return syncSource;
	}

	public void setSyncSource(String syncSource) {
		this.syncSource = syncSource;
	}

	/// How closely tracks can actually be aligned, in milliseconds.
	///
	/// State what is defensible. Across nodes this is bounded by NTP discipline,
	/// not by the sample rate, and a manifest claiming precision it cannot
	/// support is worse than one admitting the bound: the first falls apart when
	/// challenged, the second tells a reader what the evidence supports.
	@JsonPropertyDescription("Bound on cross-track alignment error in milliseconds. State what is defensible.")
	public Integer getSyncAccuracyMillis() {
		return syncAccuracyMillis;
	}

	public void setSyncAccuracyMillis(Integer syncAccuracyMillis) {
		this.syncAccuracyMillis = syncAccuracyMillis;
	}

	/// The facts an access rule matches on, such as department, queue or agent.
	///
	/// They live here, at the conversation, and every track inherits them. That
	/// is what keeps a conference affordable: adding participants multiplies
	/// storage but not the number of access decisions, and one evaluation covers
	/// the whole conversation.
	@JsonPropertyDescription("Classification an access rule matches on. Tracks inherit these.")
	public Map<String, String> getAttributes() {
		return attributes;
	}

	public void setAttributes(Map<String, String> attributes) {
		this.attributes = (attributes == null) ? new LinkedHashMap<>() : attributes;
	}

	@JsonPropertyDescription("Track ids this conversation intended to record, so a missing one is detectable.")
	public List<String> getTracksExpected() {
		return tracksExpected;
	}

	public void setTracksExpected(List<String> tracksExpected) {
		this.tracksExpected = (tracksExpected == null) ? new ArrayList<>() : tracksExpected;
	}

	@JsonPropertyDescription("The tracks themselves, recorded or explained.")
	public List<RecordingTrack> getTracks() {
		return tracks;
	}

	public void setTracks(List<RecordingTrack> tracks) {
		this.tracks = (tracks == null) ? new ArrayList<>() : tracks;
	}

	/// Transcripts of this conversation, newest last.
	///
	/// A list, never a replacement. Re-running a better model later appends an
	/// entry carrying its own engine and version rather than overwriting what a
	/// reviewer may already have acted on. Write-once storage would refuse the
	/// overwrite anyway, and keeping both is the honest record of what produced
	/// a given text.
	@JsonPropertyDescription("Transcripts of this conversation. Append versions, never overwrite.")
	public List<TranscriptRef> getTranscripts() {
		return transcripts;
	}

	public void setTranscripts(List<TranscriptRef> transcripts) {
		this.transcripts = (transcripts == null) ? new ArrayList<>() : transcripts;
	}

	/// False when this conversation was not captured as intended.
	///
	/// A node lost mid-call, a track that never started, segments dropped. An
	/// incomplete recording and a complete one are different evidence and a
	/// reviewer must not have to guess which they are holding.
	@JsonPropertyDescription("False when the conversation was not captured as intended.")
	public boolean isComplete() {
		return complete;
	}

	public void setComplete(boolean complete) {
		this.complete = complete;
	}

	@JsonPropertyDescription("Why the conversation is incomplete, when it is.")
	public String getIncompleteReason() {
		return incompleteReason;
	}

	public void setIncompleteReason(String incompleteReason) {
		this.incompleteReason = incompleteReason;
	}

	@JsonPropertyDescription("When this manifest was committed to the archive, ISO-8601.")
	public String getFinalizedUtc() {
		return finalizedUtc;
	}

	public void setFinalizedUtc(String finalizedUtc) {
		this.finalizedUtc = finalizedUtc;
	}

	@JsonPropertyDescription("Which node committed this manifest.")
	public String getFinalizedBy() {
		return finalizedBy;
	}

	public void setFinalizedBy(String finalizedBy) {
		this.finalizedBy = finalizedBy;
	}

	/// The track with this id, or null.
	public RecordingTrack track(String id) {
		for (RecordingTrack t : tracks) {
			if (t.getId() != null && t.getId().equals(id)) {
				return t;
			}
		}
		return null;
	}

	/// Add a track and record that it was intended, so the two never disagree by
	/// accident. A track added any other way is one `tracksExpected` will report
	/// as unexpected.
	public ConversationManifest addTrack(RecordingTrack track) {
		tracks.add(track);
		if (track.getId() != null && !tracksExpected.contains(track.getId())) {
			tracksExpected.add(track.getId());
		}
		return this;
	}

	public ConversationManifest addTranscript(TranscriptRef transcript) {
		transcripts.add(transcript);
		return this;
	}
}
