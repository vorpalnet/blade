package org.vorpal.blade.framework.v3.media.manifest;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One audio stream within a conversation.
///
/// A track is what a customer's choice actually selects. The same format carries
/// every shape they may ask for, and none of them is a separate code path:
///
/// | wanted | tracks |
/// |---|---|
/// | mixed only | one, role `MIX` |
/// | per participant | one `PARTICIPANT` per party |
/// | both | a `MIX` plus the participants |
/// | two-party stereo | one track, `channels` 2, with a `channelMap` |
///
/// ## Why the level is stored
///
/// [#getLevelDbfs] is a measured value, not a flag. A recording of the right
/// length and size holding nothing but digital silence looks correct in every
/// listing: a mixer emits a continuous stream whether or not anything feeds it,
/// so a dead call still produces a well formed file. That has happened here, and
/// it went unnoticed because size and duration were the only things checked.
///
/// A number is stored rather than a boolean because the number yields the
/// boolean and the boolean yields nothing.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingTrack {

	/// What this track carries.
	public enum Role {
		/// The mix of every other participant, taken from a hub port that
		/// contributes nothing. One recorder captures the whole conversation
		/// however many parties there are.
		MIX,
		/// One party, taken from that party's own leg. Keeps speaker separation,
		/// which scoring a voice for synthetic speech depends on and which a mix
		/// destroys.
		PARTICIPANT
	}

	/// Whether the track exists, and if not, why not.
	///
	/// The distinction is the point. A track absent because a party withheld
	/// consent and a track absent because a node died look identical in storage
	/// and mean opposite things to an auditor.
	public enum State {
		/// Captured.
		RECORDED,
		/// Deliberately not captured. Consent withheld, or policy. Not a fault.
		NOT_RECORDED,
		/// Intended and did not survive. A fault, and a reviewer must see it.
		FAILED
	}

	private String id;
	private Role role;
	private State state = State.RECORDED;
	private String stateReason;
	private RecordingParty party;
	private String node;
	private TrackClock clock;
	private Long offsetMillis;
	private Long durationMillis;
	private String codec;
	private Integer rate;
	private Integer channels;
	private List<String> channelMap = new ArrayList<>();
	private Double levelDbfs;
	private List<MediaGap> gaps = new ArrayList<>();
	private List<MediaSegment> segments = new ArrayList<>();

	public RecordingTrack() {
	}

	public RecordingTrack(String id, Role role) {
		this.id = id;
		this.role = role;
	}

	@JsonPropertyDescription("Track identifier, unique within the conversation.")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Whether this track is the mix or one participant.")
	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
	}

	@JsonPropertyDescription("Whether the track was recorded, deliberately skipped, or failed.")
	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}

	@JsonPropertyDescription("Why the track was skipped or failed, when it was.")
	public String getStateReason() {
		return stateReason;
	}

	public void setStateReason(String stateReason) {
		this.stateReason = stateReason;
	}

	@JsonPropertyDescription("The party this track carries. Absent on a mix.")
	public RecordingParty getParty() {
		return party;
	}

	public void setParty(RecordingParty party) {
		this.party = party;
	}

	@JsonPropertyDescription("Which node recorded this track. A failover can move it.")
	public String getNode() {
		return node;
	}

	public void setNode(String node) {
		this.node = node;
	}

	@JsonPropertyDescription("How this track's media clock maps onto absolute time.")
	public TrackClock getClock() {
		return clock;
	}

	public void setClock(TrackClock clock) {
		this.clock = clock;
	}

	/// Where this track starts, relative to the conversation epoch.
	///
	/// Non-zero for a party who joined late, which is the ordinary case in a
	/// conference and after a transfer. Without it a reader has to guess whether
	/// a short track started late or ended early.
	@JsonPropertyDescription("Start of this track relative to the conversation epoch, in milliseconds.")
	public Long getOffsetMillis() {
		return offsetMillis;
	}

	public void setOffsetMillis(Long offsetMillis) {
		this.offsetMillis = offsetMillis;
	}

	@JsonPropertyDescription("Length of this track in milliseconds, including its gaps.")
	public Long getDurationMillis() {
		return durationMillis;
	}

	public void setDurationMillis(Long durationMillis) {
		this.durationMillis = durationMillis;
	}

	@JsonPropertyDescription("Stored audio encoding, for example aac or opus.")
	public String getCodec() {
		return codec;
	}

	public void setCodec(String codec) {
		this.codec = codec;
	}

	/// Sample rate as stored.
	///
	/// A leg carries what the call carries, commonly 8000. A hub's mix is its
	/// own rate and commonly 48000. Participants on different codecs give tracks
	/// different rates within one conversation, so a consumer expecting matched
	/// channels has to read this rather than assume.
	@JsonPropertyDescription("Stored sample rate in Hz. Tracks in one conversation may differ.")
	public Integer getRate() {
		return rate;
	}

	public void setRate(Integer rate) {
		this.rate = rate;
	}

	@JsonPropertyDescription("Stored channel count.")
	public Integer getChannels() {
		return channels;
	}

	public void setChannels(Integer channels) {
		this.channels = channels;
	}

	/// Which party is on each channel, when a track carries more than one.
	///
	/// This is how two-party stereo is expressed without a second track: one
	/// entry per channel, naming the party, so a reviewer knows which side they
	/// are hearing.
	@JsonPropertyDescription("Party id per channel, for a multi-channel track.")
	public List<String> getChannelMap() {
		return channelMap;
	}

	public void setChannelMap(List<String> channelMap) {
		this.channelMap = (channelMap == null) ? new ArrayList<>() : channelMap;
	}

	/// Measured mean level in dBFS. See the class note: this is why silence is
	/// visible. Around -91 is digital silence for 16-bit audio.
	@JsonPropertyDescription("Measured mean level in dBFS. Near -91 is digital silence.")
	public Double getLevelDbfs() {
		return levelDbfs;
	}

	public void setLevelDbfs(Double levelDbfs) {
		this.levelDbfs = levelDbfs;
	}

	@JsonPropertyDescription("Intervals where no media was captured, and why.")
	public List<MediaGap> getGaps() {
		return gaps;
	}

	public void setGaps(List<MediaGap> gaps) {
		this.gaps = (gaps == null) ? new ArrayList<>() : gaps;
	}

	@JsonPropertyDescription("The stored objects making up this track, in order.")
	public List<MediaSegment> getSegments() {
		return segments;
	}

	public void setSegments(List<MediaSegment> segments) {
		this.segments = (segments == null) ? new ArrayList<>() : segments;
	}

	public RecordingTrack addSegment(MediaSegment segment) {
		segments.add(segment);
		return this;
	}

	public RecordingTrack addGap(MediaGap gap) {
		gaps.add(gap);
		return this;
	}

	/// Total stored bytes across this track's segments.
	public long bytes() {
		long total = 0;
		for (MediaSegment s : segments) {
			if (s.getBytes() != null) {
				total += s.getBytes();
			}
		}
		return total;
	}

	/// Milliseconds of this track that carry media, which is its length less
	/// everything a gap accounts for. The difference between this and
	/// [#getDurationMillis] is what a player fills with silence.
	public long recordedMillis() {
		long span = (durationMillis == null) ? 0 : durationMillis;
		for (MediaGap g : gaps) {
			span -= g.lengthMillis();
		}
		return Math.max(span, 0);
	}
}
