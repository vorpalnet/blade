package org.vorpal.blade.framework.v3.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.concurrent.CopyOnWriteArrayList;

import javax.media.mscontrol.mediagroup.PlayerEvent;

import org.junit.jupiter.api.Test;

/// One dispatcher per app, resource and verb, so a verb armed again (a prompt that loops) never
/// leaves two: with two, the event that finished one play started two more.
class MediaDispatcherTest {

	@Test
	void dispatchersForTheSameContinuationAreEqual() {
		MediaCallflow.MediaDispatcher<PlayerEvent> a = new MediaCallflow.MediaDispatcher<>("app", "mscontrol://ms1", "play");
		MediaCallflow.MediaDispatcher<PlayerEvent> b = new MediaCallflow.MediaDispatcher<>("app", "mscontrol://ms1", "play");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, new MediaCallflow.MediaDispatcher<>("app", "mscontrol://ms1", "record"));
		assertNotEquals(a, new MediaCallflow.MediaDispatcher<>("other", "mscontrol://ms1", "play"));
	}

	@Test
	void armingAgainLeavesOneOnAListBackedNotifier() {
		// A driver may keep listeners in a list, as Gryphon's does; arm removes an equal one first.
		CopyOnWriteArrayList<Object> listeners = new CopyOnWriteArrayList<>();
		for (int i = 0; i < 5; i++) {
			MediaCallflow.MediaDispatcher<PlayerEvent> d = new MediaCallflow.MediaDispatcher<>("app", "mscontrol://ms1", "play");
			listeners.remove(d);
			listeners.add(d);
		}
		assertEquals(1, listeners.size());
	}
}
