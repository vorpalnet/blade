package org.vorpal.blade.framework.v3.media.manifest;

import java.time.Instant;
import java.util.List;

import org.vorpal.blade.framework.v3.media.manifest.TrackClock.SyncPoint;

/// Places a track's RTP timestamps on the conversation's timeline.
///
/// Everything in a manifest is an offset in milliseconds from
/// [ConversationManifest#getEpochUtc]. This turns a stream's own RTP timestamp
/// into one of those offsets, which is what lets a transcript line, a segment
/// boundary and a second participant's audio all refer to the same moment.
///
/// ## How a timestamp is placed
///
/// Between two anchors, interpolation uses the rate measured *between those two
/// anchors*, not the declared one. That is the whole point of keeping more than
/// one: a sender running 40 ppm fast is placed correctly at minute forty instead
/// of drifting a hundred milliseconds away from everyone else.
///
/// Outside the anchors it extrapolates from the nearest pair, falling back to
/// the declared rate when only one anchor exists. A single anchor is enough to
/// be useful and not enough to see drift, and [#isDriftAware] says which case a
/// caller is in.
///
/// ## What it will not do
///
/// Guess. With no anchors a track cannot be placed on the timeline at all, and
/// this throws rather than returning a plausible number computed from a declared
/// rate and an assumed start. A manifest whose offsets are partly measured and
/// partly invented is worse than one that admits a track is unplaceable, because
/// nothing downstream can tell the two apart.
public final class ConversationTime {

	private final long epochMillis;
	private final TrackClock clock;

	private ConversationTime(long epochMillis, TrackClock clock) {
		this.epochMillis = epochMillis;
		this.clock = clock;
	}

	/// A placer for one track of one conversation.
	///
	/// @throws IllegalArgumentException if the conversation has no epoch, or the
	///         track has no clock with at least one anchor
	public static ConversationTime of(ConversationManifest manifest, RecordingTrack track) {
		if (manifest == null || manifest.getEpochUtc() == null) {
			throw new IllegalArgumentException("a conversation without an epoch has no timeline");
		}
		if (track == null || track.getClock() == null || track.getClock().getSyncPoints().isEmpty()) {
			throw new IllegalArgumentException(
					"track " + ((track == null) ? null : track.getId()) + " has no clock anchor, so it cannot be placed");
		}
		return new ConversationTime(manifest.epoch().toEpochMilli(), track.getClock());
	}

	/// Whether this track has enough anchors to correct for sender drift. With
	/// one anchor the declared rate is all there is to go on.
	public boolean isDriftAware() {
		return clock.getSyncPoints().size() >= 2;
	}

	/// The conversation offset, in milliseconds, of an RTP timestamp.
	public long offsetOf(long rtp) {
		return utcMillisOf(rtp) - epochMillis;
	}

	/// The absolute instant of an RTP timestamp.
	public Instant instantOf(long rtp) {
		return Instant.ofEpochMilli(utcMillisOf(rtp));
	}

	/// The absolute time of an RTP timestamp, in epoch milliseconds.
	public long utcMillisOf(long rtp) {
		List<SyncPoint> points = clock.getSyncPoints();
		if (points.size() == 1) {
			SyncPoint only = points.get(0);
			return only.utcMillis() + Math.round(ticksToMillis(TrackClock.rtpDistance(only.getRtp(), rtp), declaredRate()));
		}

		SyncPoint before = points.get(0);
		SyncPoint after = points.get(1);
		for (int i = 0; i + 1 < points.size(); i++) {
			SyncPoint a = points.get(i);
			SyncPoint b = points.get(i + 1);
			// Forward distance is unsigned, so "rtp sits between a and b" is
			// exactly "the distance from a to rtp is no further than a to b".
			if (TrackClock.rtpDistance(a.getRtp(), rtp) <= TrackClock.rtpDistance(a.getRtp(), b.getRtp())) {
				before = a;
				after = b;
				break;
			}
			before = a;
			after = b;
		}

		double rate = rateBetween(before, after);
		long ticks = TrackClock.rtpDistance(before.getRtp(), rtp);
		return before.utcMillis() + Math.round(ticksToMillis(ticks, rate));
	}

	/// The rate actually observed between two anchors, falling back to the
	/// declared rate when they carry no usable span.
	private double rateBetween(SyncPoint a, SyncPoint b) {
		long millis = b.utcMillis() - a.utcMillis();
		if (millis <= 0) {
			return declaredRate();
		}
		long ticks = TrackClock.rtpDistance(a.getRtp(), b.getRtp());
		if (ticks == 0) {
			return declaredRate();
		}
		return (ticks * 1000.0) / millis;
	}

	private double declaredRate() {
		Integer declared = clock.getRtpRate();
		if (declared == null || declared <= 0) {
			throw new IllegalStateException("track clock has no usable rate");
		}
		return declared;
	}

	private static double ticksToMillis(long ticks, double rate) {
		return (ticks * 1000.0) / rate;
	}
}
