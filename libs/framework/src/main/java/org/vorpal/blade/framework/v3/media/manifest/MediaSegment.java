package org.vorpal.blade.framework.v3.media.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// One stored object making up part of a track.
///
/// Segments are immutable from birth. A segment is written once and never
/// changes, which is why they go straight into write-once storage while the
/// manifest that describes them lives somewhere mutable until the conversation
/// closes.
///
/// ## Why each segment carries its own timing
///
/// So a hole is visible without decoding anything. A reader comparing one
/// segment's end against the next segment's start sees the discontinuity, and
/// [RecordingTrack#getGaps] says whether it was a hold, card entry, or a node
/// that died. A segment list carrying only an order and a size cannot
/// distinguish a recording that paused from one that lost three minutes.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaSegment {

	private Integer n;
	private Long startMillis;
	private Long durationMillis;
	private String object;
	private Long bytes;

	public MediaSegment() {
	}

	public MediaSegment(int n, long startMillis, long durationMillis, String object) {
		this.n = n;
		this.startMillis = startMillis;
		this.durationMillis = durationMillis;
		this.object = object;
	}

	@JsonPropertyDescription("Position of this segment within its track, from zero.")
	public Integer getN() {
		return n;
	}

	public void setN(Integer n) {
		this.n = n;
	}

	@JsonPropertyDescription("Start of this segment relative to the conversation epoch, in milliseconds.")
	public Long getStartMillis() {
		return startMillis;
	}

	public void setStartMillis(Long startMillis) {
		this.startMillis = startMillis;
	}

	@JsonPropertyDescription("Length of this segment in milliseconds.")
	public Long getDurationMillis() {
		return durationMillis;
	}

	public void setDurationMillis(Long durationMillis) {
		this.durationMillis = durationMillis;
	}

	@JsonPropertyDescription("Stored object name, relative to the track's prefix.")
	public String getObject() {
		return object;
	}

	public void setObject(String object) {
		this.object = object;
	}

	@JsonPropertyDescription("Stored size in bytes.")
	public Long getBytes() {
		return bytes;
	}

	public void setBytes(Long bytes) {
		this.bytes = bytes;
	}

	/// Where this segment ends, relative to the conversation epoch.
	public long endMillis() {
		long start = (startMillis == null) ? 0 : startMillis;
		long length = (durationMillis == null) ? 0 : durationMillis;
		return start + length;
	}
}
