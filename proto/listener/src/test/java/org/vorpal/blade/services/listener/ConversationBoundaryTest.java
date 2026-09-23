package org.vorpal.blade.services.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.media.MediaDirection;

/// The recorder's whole decision, tested without a container.
class ConversationBoundaryTest {

	private static final List<MediaDirection> ACTIVE = Collections.singletonList(MediaDirection.SENDRECV);
	private static final List<MediaDirection> HELD = Collections.singletonList(MediaDirection.SENDONLY);
	private static final List<MediaDirection> INACTIVE = Collections.singletonList(MediaDirection.INACTIVE);

	private static final boolean RUNNING = false;
	private static final boolean PAUSED = true;
	private static final boolean INITIAL = true;
	private static final boolean REINVITE = false;

	@Nested
	@DisplayName("conversation boundaries")
	class Boundaries {

		@Test
		@DisplayName("an initial INVITE starts a conversation")
		void initialStartsAConversation() {
			assertEquals(RecordingAction.START_CONVERSATION,
					ConversationBoundary.decide(INITIAL, ACTIVE, RUNNING));
		}

		@Test
		@DisplayName("a second initial INVITE starts another, which is the transfer case")
		void secondInitialStartsAnother() {
			// The call moved to a new party. This application learns that from the
			// App Router routing another initial request through it, and needs to
			// know nothing about how the transfer was performed.
			assertEquals(RecordingAction.START_CONVERSATION,
					ConversationBoundary.decide(INITIAL, ACTIVE, RUNNING));
		}

		@Test
		@DisplayName("an initial INVITE starts a conversation even while paused")
		void initialWinsOverPaused() {
			// A conversation that begins while the previous one was on hold is
			// still a new conversation. Resuming the old recording here would put
			// a new party's audio into the previous party's recording.
			assertEquals(RecordingAction.START_CONVERSATION,
					ConversationBoundary.decide(INITIAL, ACTIVE, PAUSED));
		}

		@Test
		@DisplayName("a re-INVITE never starts a conversation")
		void reInviteIsNeverABoundary() {
			// Same dialog, same parties, by definition.
			assertEquals(RecordingAction.NONE, ConversationBoundary.decide(REINVITE, ACTIVE, RUNNING));
		}
	}

	@Nested
	@DisplayName("hold")
	class Hold {

		@Test
		@DisplayName("sendonly pauses a running recording")
		void sendonlyPauses() {
			assertEquals(RecordingAction.PAUSE, ConversationBoundary.decide(REINVITE, HELD, RUNNING));
		}

		@Test
		@DisplayName("inactive pauses a running recording")
		void inactivePauses() {
			assertEquals(RecordingAction.PAUSE, ConversationBoundary.decide(REINVITE, INACTIVE, RUNNING));
		}

		@Test
		@DisplayName("coming off hold resumes into the same recording")
		void resumeReturnsToTheSameRecording() {
			assertEquals(RecordingAction.RESUME, ConversationBoundary.decide(REINVITE, ACTIVE, PAUSED));
		}

		@Test
		@DisplayName("a repeated hold does not pause twice")
		void repeatedHoldIsIdempotent() {
			assertEquals(RecordingAction.NONE, ConversationBoundary.decide(REINVITE, HELD, PAUSED));
		}

		@Test
		@DisplayName("a repeated unhold does not resume twice")
		void repeatedUnholdIsIdempotent() {
			assertEquals(RecordingAction.NONE, ConversationBoundary.decide(REINVITE, ACTIVE, RUNNING));
		}

		@Test
		@DisplayName("recvonly is not hold, so the call keeps recording")
		void recvonlyIsNotHold() {
			// The far party is still receiving, so the conversation may still be
			// audible. A compliance recording errs toward capturing rather than
			// toward a gap it cannot account for.
			List<MediaDirection> recvonly = Collections.singletonList(MediaDirection.RECVONLY);

			assertFalse(ConversationBoundary.isHold(recvonly));
			assertEquals(RecordingAction.NONE, ConversationBoundary.decide(REINVITE, recvonly, RUNNING));
		}

		@Test
		@DisplayName("a partial hold is not a hold")
		void partialHoldKeepsRecording() {
			// Audio held, video still running. Something is still flowing, so
			// stopping the recorder would drop content that was live.
			List<MediaDirection> mixed = Arrays.asList(MediaDirection.SENDONLY, MediaDirection.SENDRECV);

			assertFalse(ConversationBoundary.isHold(mixed));
			assertEquals(RecordingAction.NONE, ConversationBoundary.decide(REINVITE, mixed, RUNNING));
		}

		@Test
		@DisplayName("every m-line held is a hold")
		void everyLineHeldIsAHold() {
			assertTrue(ConversationBoundary.isHold(
					Arrays.asList(MediaDirection.SENDONLY, MediaDirection.INACTIVE)));
		}
	}

	@Nested
	@DisplayName("offerless and malformed")
	class NoOffer {

		@Test
		@DisplayName("an offerless re-INVITE is a session refresh and means nothing")
		void offerlessRefreshDoesNothing() {
			assertEquals(RecordingAction.NONE, ConversationBoundary.decide(REINVITE, null, RUNNING));
			assertEquals(RecordingAction.NONE,
					ConversationBoundary.decide(REINVITE, Collections.<MediaDirection>emptyList(), RUNNING));
		}

		@Test
		@DisplayName("an offerless refresh does not resume a paused recording")
		void offerlessRefreshDoesNotResume() {
			// A session refresh while on hold must leave the recorder paused. The
			// parties have not come back; the dialog is merely being kept alive.
			assertEquals(RecordingAction.NONE, ConversationBoundary.decide(REINVITE, null, PAUSED));
		}

		@Test
		@DisplayName("an offerless initial INVITE still starts a conversation")
		void offerlessInitialStillStarts() {
			// Late media. The offer arrives later, but the conversation began.
			assertEquals(RecordingAction.START_CONVERSATION,
					ConversationBoundary.decide(INITIAL, null, RUNNING));
		}

		@Test
		@DisplayName("an empty direction list is not a hold")
		void emptyIsNotHold() {
			assertFalse(ConversationBoundary.isHold(null));
			assertFalse(ConversationBoundary.isHold(Collections.<MediaDirection>emptyList()));
		}
	}
}
