package org.vorpal.blade.framework.v3.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.media.mscontrol.EventType;
import javax.media.mscontrol.MediaErr;
import javax.media.mscontrol.MediaEvent;
import javax.media.mscontrol.mediagroup.RecorderEvent;
import javax.media.mscontrol.mediagroup.signals.SignalDetectorEvent;
import javax.media.mscontrol.resource.AllocationEvent;
import javax.media.mscontrol.resource.ResourceContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;

/// The parts of [MediaCallflow] that reach a driver through nothing but JSR-309 and strings: a DTMF
/// collect completed from SIP INFO, media-server loss arriving as an [AllocationEvent], and the
/// dispatcher letting progress reports pass a continuation by.
class MediaCallflowOutOfBandTest {

	/// A collect's continuation key, as `arm` writes it for a `prompt` on this session.
	private static final String COLLECT_KEY = "org.vorpal.blade.v3.media.cb.media:session:x:COLLECT";

	@AfterEach
	void clearListener() {
		MediaCallflow.setMediaLostListener(null);
	}

	private static DetachedApplicationSession pendingCollect(int digits, String[] got) {
		DetachedApplicationSession app = new DetachedApplicationSession("t");
		Callback<SignalDetectorEvent> onDigits = event -> got[0] = event.getSignalString();
		app.setAttribute(COLLECT_KEY, onDigits);
		app.setAttribute(COLLECT_KEY + ".wanted", digits);
		return app;
	}

	@Test
	void infoDigitsCompleteTheCollectOnCount() {
		String[] got = new String[1];
		DetachedApplicationSession app = pendingCollect(4, got);

		assertTrue(MediaCallflow.deliverDtmf(app, "12"));
		assertNull(got[0], "not complete until the 4th digit");
		assertEquals("12", app.getAttribute(COLLECT_KEY + ".heard"), "the digits so far ride the app session");

		assertTrue(MediaCallflow.deliverDtmf(app, "34"));
		assertEquals("1234", got[0]);
		assertNull(app.getAttribute(COLLECT_KEY), "one-shot, like a response callback");
		assertNull(app.getAttribute(COLLECT_KEY + ".wanted"));
		assertNull(app.getAttribute(COLLECT_KEY + ".heard"));

		assertFalse(MediaCallflow.deliverDtmf(app, "5"), "nothing collecting any more");
	}

	@Test
	void aHashEndsAVariableLengthCollect() {
		String[] got = new String[1];
		DetachedApplicationSession app = pendingCollect(0, got);

		MediaCallflow.deliverDtmf(app, "678#9");

		assertEquals("678", got[0], "the # ends the collect and is not part of it");
	}

	@Test
	void digitsWithNoPromptAreDropped() {
		assertFalse(MediaCallflow.deliverDtmf(new DetachedApplicationSession("t"), "5"));
		assertFalse(MediaCallflow.deliverDtmf(null, "5"));
	}

	@Test
	void theCompletionLooksLikeTheDetectorsOwn() {
		SignalDetectorEvent[] got = new SignalDetectorEvent[1];
		DetachedApplicationSession app = new DetachedApplicationSession("t");
		Callback<SignalDetectorEvent> onDigits = event -> got[0] = event;
		app.setAttribute(COLLECT_KEY, onDigits);
		app.setAttribute(COLLECT_KEY + ".wanted", 1);

		MediaCallflow.deliverDtmf(app, "7");

		assertEquals(SignalDetectorEvent.RECEIVE_SIGNALS_COMPLETED, got[0].getEventType());
		assertTrue(got[0].isSuccessful());
		assertEquals(MediaEvent.NO_ERROR, got[0].getError());
	}

	@Test
	void anIrrecoverableFailureReachesTheApplicationOnce() {
		List<String> lost = new ArrayList<>();
		MediaCallflow.setMediaLostListener((appId, msUri) -> lost.add(appId + " " + msUri));
		MediaCallflow.MediaLossListener listener = new MediaCallflow.MediaLossListener("app-1", "media:session:x");

		listener.onEvent(allocation(AllocationEvent.ALLOCATION_CONFIRMED));
		assertTrue(lost.isEmpty(), "a confirmed allocation is not a loss");

		listener.onEvent(allocation(AllocationEvent.IRRECOVERABLE_FAILURE));
		assertEquals(List.of("app-1 media:session:x"), lost);
	}

	@Test
	void lossListenersAreEqualPerSession() {
		assertEquals(new MediaCallflow.MediaLossListener("app-1", "media:session:x"),
				new MediaCallflow.MediaLossListener("app-1", "media:session:x"),
				"a driver that keeps listeners as a set holds one per container");
		assertEquals(new MediaCallflow.MediaLossListener("app-1", "media:session:x").hashCode(),
				new MediaCallflow.MediaLossListener("app-1", "media:session:x").hashCode());
		assertNotEquals(new MediaCallflow.MediaLossListener("app-1", "media:session:x"),
				new MediaCallflow.MediaLossListener("app-1", "media:session:y"));
	}

	@Test
	void progressReportsPassAContinuationBy() {
		assertTrue(MediaCallflow.isProgress(event(RecorderEvent.PAUSED)));
		assertTrue(MediaCallflow.isProgress(event(RecorderEvent.RESUMED)));
		assertTrue(MediaCallflow.isProgress(event(SignalDetectorEvent.SIGNAL_DETECTED)));
		assertFalse(MediaCallflow.isProgress(event(RecorderEvent.RECORD_COMPLETED)));
		assertFalse(MediaCallflow.isProgress(event(SignalDetectorEvent.RECEIVE_SIGNALS_COMPLETED)));
	}

	private static MediaEvent<Object> event(EventType type) {
		return new MediaEvent<Object>() {
			@Override
			public Object getSource() {
				return null;
			}

			@Override
			public EventType getEventType() {
				return type;
			}

			@Override
			public boolean isSuccessful() {
				return true;
			}

			@Override
			public MediaErr getError() {
				return MediaEvent.NO_ERROR;
			}

			@Override
			public String getErrorText() {
				return null;
			}
		};
	}

	private static AllocationEvent allocation(EventType type) {
		return new AllocationEvent() {
			@Override
			public ResourceContainer getSource() {
				return null;
			}

			@Override
			public EventType getEventType() {
				return type;
			}

			@Override
			public boolean isSuccessful() {
				return false;
			}

			@Override
			public MediaErr getError() {
				return MediaErr.UNKNOWN_ERROR;
			}

			@Override
			public String getErrorText() {
				return "media server connection lost";
			}
		};
	}
}
