package org.vorpal.blade.framework.v3.media.manifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// How one track's media clock maps onto absolute time.
///
/// Offsets in this format are media time derived from RTP, anchored to UTC at
/// known points. Server wall clock is used only for the anchors and never for
/// durations, for a reason worth stating: an NTP step part way through a call
/// silently misplaces everything after it, and the result looks entirely
/// plausible. RTP timestamps are monotonic per stream and immune to that.
///
/// ## Why anchors, and why more than one
///
/// Each party's RTP clock starts at an arbitrary value, so RTP alone cannot
/// place two participants on a common timeline. An RTCP sender report carries
/// the sender's own NTP-to-RTP correspondence, which is the only principled way
/// to align streams from different sources. That is what a [SyncPoint] records.
///
/// More than one, because two endpoints nominally at 8000 Hz are not actually at
/// the same rate. Over a long conference the tracks drift apart. Periodic
/// anchors let a reader interpolate against the rate a sender is *actually*
/// running at, so speakers stay aligned at minute forty as well as minute one.
/// [#driftPpm] reports how far from nominal a sender turned out to be.
///
/// ## Wrapping
///
/// RTP timestamps are 32 bits and wrap roughly every six days at 8 kHz, and far
/// sooner at 48 kHz. Distances are computed as forward distances modulo 2^32,
/// so a wrap between two anchors is handled rather than producing a negative
/// span. This assumes anchors are in order, which they are: they are appended as
/// sender reports arrive.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrackClock {

	/// The clock rate a stream declares, against which drift is measured.
	private Integer rtpRate;
	private List<SyncPoint> syncPoints = new ArrayList<>();

	/// One correspondence between a stream's RTP timestamp and absolute UTC.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SyncPoint {

		private Long rtp;
		private String utc;
		private String from;

		public SyncPoint() {
		}

		public SyncPoint(long rtp, Instant utc, String from) {
			this.rtp = rtp;
			this.utc = (utc == null) ? null : utc.toString();
			this.from = from;
		}

		@JsonPropertyDescription("RTP timestamp at this anchor.")
		public Long getRtp() {
			return rtp;
		}

		public void setRtp(Long rtp) {
			this.rtp = rtp;
		}

		@JsonPropertyDescription("Absolute UTC instant this RTP timestamp corresponds to, ISO-8601.")
		public String getUtc() {
			return utc;
		}

		public void setUtc(String utc) {
			this.utc = utc;
		}

		/// Where the correspondence came from, such as `rtcp-sr` for a sender
		/// report or `local-receipt` when the only thing available was the
		/// instant a packet arrived. They do not deserve equal confidence and a
		/// reader is entitled to know which it is looking at.
		@JsonPropertyDescription("Origin of this anchor, for example rtcp-sr or local-receipt.")
		public String getFrom() {
			return from;
		}

		public void setFrom(String from) {
			this.from = from;
		}

		public Instant instant() {
			return (utc == null) ? null : Instant.parse(utc);
		}

		public long utcMillis() {
			Instant i = instant();
			return (i == null) ? 0 : i.toEpochMilli();
		}
	}

	public TrackClock() {
	}

	public TrackClock(int rtpRate) {
		this.rtpRate = rtpRate;
	}

	@JsonPropertyDescription("Declared RTP clock rate in Hz, against which drift is measured.")
	public Integer getRtpRate() {
		return rtpRate;
	}

	public void setRtpRate(Integer rtpRate) {
		this.rtpRate = rtpRate;
	}

	@JsonPropertyDescription("RTP to UTC anchors, in order. More than one captures sender drift.")
	public List<SyncPoint> getSyncPoints() {
		return syncPoints;
	}

	public void setSyncPoints(List<SyncPoint> syncPoints) {
		this.syncPoints = (syncPoints == null) ? new ArrayList<>() : syncPoints;
	}

	public TrackClock anchor(long rtp, Instant utc, String from) {
		syncPoints.add(new SyncPoint(rtp, utc, from));
		return this;
	}

	/// Forward distance between two RTP timestamps, handling the 32-bit wrap.
	public static long rtpDistance(long from, long to) {
		return (to - from) & 0xFFFFFFFFL;
	}

	/// The rate this sender was actually running at, in Hz, measured between the
	/// first and last anchor. Null when there are fewer than two anchors or they
	/// carry no usable time span.
	///
	/// This is the measured rate, not the declared one. Interpolating with the
	/// declared rate is what lets two tracks slide apart over a long call.
	public Double measuredRate() {
		if (syncPoints.size() < 2) {
			return null;
		}
		SyncPoint first = syncPoints.get(0);
		SyncPoint last = syncPoints.get(syncPoints.size() - 1);
		if (first.getRtp() == null || last.getRtp() == null) {
			return null;
		}
		long millis = last.utcMillis() - first.utcMillis();
		if (millis <= 0) {
			return null;
		}
		long ticks = rtpDistance(first.getRtp(), last.getRtp());
		return (ticks * 1000.0) / millis;
	}

	/// How far the sender's actual rate sits from the rate it declared, in parts
	/// per million. Null when it cannot be measured.
	///
	/// Worth keeping because it is the number that explains why two tracks need
	/// resampling to stay aligned, and because a wild value is a sign the anchors
	/// are not what they claim to be.
	public Double driftPpm() {
		Double measured = measuredRate();
		if (measured == null || rtpRate == null || rtpRate <= 0) {
			return null;
		}
		return ((measured - rtpRate) / rtpRate) * 1_000_000.0;
	}
}
