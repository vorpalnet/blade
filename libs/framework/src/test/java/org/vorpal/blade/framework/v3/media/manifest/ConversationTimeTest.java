package org.vorpal.blade.framework.v3.media.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/// Placing RTP timestamps on the conversation timeline.
public class ConversationTimeTest {

	private static final Instant EPOCH = Instant.parse("2026-09-07T04:11:01.000Z");

	private ConversationManifest conversation() {
		return new ConversationManifest("conv-1", "call-1", EPOCH);
	}

	private RecordingTrack trackWith(TrackClock clock) {
		RecordingTrack track = new RecordingTrack("p1", RecordingTrack.Role.PARTICIPANT);
		track.setClock(clock);
		return track;
	}

	@Test
	public void oneAnchorPlacesTimestampsWithTheDeclaredRate() {
		TrackClock clock = new TrackClock(8000);
		clock.anchor(1_000_000L, EPOCH, "rtcp-sr");

		ConversationTime time = ConversationTime.of(conversation(), trackWith(clock));

		assertFalse(time.isDriftAware(), "a single anchor cannot show drift");
		// 8000 ticks at 8kHz is one second.
		assertEquals(1000L, time.offsetOf(1_008_000L));
		assertEquals(0L, time.offsetOf(1_000_000L));
	}

	@Test
	public void interpolationUsesTheRateActuallyObserved() {
		// A sender that declares 8000Hz but really runs 8080Hz: 20200 ticks
		// elapse across a wall-clock 2.5s.
		TrackClock clock = new TrackClock(8000);
		clock.anchor(1_000_000L, EPOCH, "rtcp-sr");
		clock.anchor(1_020_200L, EPOCH.plusMillis(2500), "rtcp-sr");

		ConversationTime time = ConversationTime.of(conversation(), trackWith(clock));

		assertTrue(time.isDriftAware());
		// Half way between the anchors in ticks is half way in time, which the
		// declared rate would have placed at 1262ms instead of 1250ms.
		assertEquals(1250L, time.offsetOf(1_010_100L));
		assertEquals(2500L, time.offsetOf(1_020_200L));
	}

	@Test
	public void driftIsReportedAgainstTheDeclaredRate() {
		TrackClock clock = new TrackClock(8000);
		clock.anchor(0L, EPOCH, "rtcp-sr");
		clock.anchor(80_800L, EPOCH.plusMillis(10_000), "rtcp-sr");

		// 80800 ticks in 10s is 8080Hz, which is 10000 ppm fast.
		assertEquals(8080.0, clock.measuredRate(), 0.001);
		assertEquals(10_000.0, clock.driftPpm(), 0.5);
	}

	@Test
	public void aTimestampAfterTheLastAnchorExtrapolates() {
		TrackClock clock = new TrackClock(8000);
		clock.anchor(0L, EPOCH, "rtcp-sr");
		clock.anchor(8_000L, EPOCH.plusMillis(1000), "rtcp-sr");

		ConversationTime time = ConversationTime.of(conversation(), trackWith(clock));

		assertEquals(5000L, time.offsetOf(40_000L));
	}

	/// RTP timestamps are 32 bits and wrap. A wrap between two anchors has to
	/// read as a forward span, not a negative one.
	@Test
	public void aWrappedTimestampIsAForwardDistance() {
		long justBeforeWrap = 0xFFFFFF00L;
		TrackClock clock = new TrackClock(8000);
		clock.anchor(justBeforeWrap, EPOCH, "rtcp-sr");

		ConversationTime time = ConversationTime.of(conversation(), trackWith(clock));

		// 0x100 ticks past the anchor lands at 0, having wrapped.
		assertEquals(256L, TrackClock.rtpDistance(justBeforeWrap, 0L));
		assertEquals(32L, time.offsetOf(0L));
	}

	@Test
	public void aTrackWithoutAnAnchorIsUnplaceableRatherThanGuessed() {
		RecordingTrack track = new RecordingTrack("p1", RecordingTrack.Role.PARTICIPANT);
		track.setClock(new TrackClock(8000));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> ConversationTime.of(conversation(), track));
		assertTrue(e.getMessage().contains("cannot be placed"));
	}

	@Test
	public void aConversationWithoutAnEpochHasNoTimeline() {
		ConversationManifest manifest = new ConversationManifest("conv-1", "call-1", null);
		TrackClock clock = new TrackClock(8000);
		clock.anchor(0L, EPOCH, "rtcp-sr");

		assertThrows(IllegalArgumentException.class, () -> ConversationTime.of(manifest, trackWith(clock)));
	}

	@Test
	public void twoParticipantsWithDifferentClocksLandOnOneTimeline() {
		// Alice's stream starts at an arbitrary RTP origin; Bob joins 8.4s in
		// with a different origin and a different rate. Both must resolve to the
		// conversation's own clock.
		TrackClock alice = new TrackClock(8000);
		alice.anchor(500L, EPOCH, "rtcp-sr");
		alice.anchor(80_500L, EPOCH.plusMillis(10_000), "rtcp-sr");

		TrackClock bob = new TrackClock(48000);
		bob.anchor(900_000L, EPOCH.plusMillis(8400), "rtcp-sr");
		bob.anchor(900_000L + 48_000L, EPOCH.plusMillis(9400), "rtcp-sr");

		ConversationManifest manifest = conversation();
		ConversationTime aliceTime = ConversationTime.of(manifest, trackWith(alice));
		ConversationTime bobTime = ConversationTime.of(manifest, trackWith(bob));

		assertEquals(5000L, aliceTime.offsetOf(40_500L));
		assertEquals(9400L, bobTime.offsetOf(948_000L));
	}
}
