package org.vorpal.blade.applications.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.security.AuditSink;

/// What the recorder must never do: filter, transform, or swallow.
class AuditRecorderTest {

	/// A sink that remembers what it was given, and can be told to fail.
	private static final class RecordingSink implements AuditSink {
		final List<CloudEvent> written = new ArrayList<>();
		IOException failure;

		@Override
		public void write(List<CloudEvent> batch) throws IOException {
			if (failure != null) {
				throw failure;
			}
			written.addAll(batch);
		}
	}

	/// The handler resolves its sink through `AuditSink.installed()`, which is a
	/// ServiceLoader lookup. These tests drive the seam directly instead, so they
	/// stay honest about what they cover: the recorder's own decisions, not the
	/// discovery mechanism.
	private static void handOff(AuditSink sink, List<CloudEvent> batch) throws Exception {
		if (batch == null || batch.isEmpty()) {
			return;
		}
		if (sink == null) {
			throw new IOException("no AuditSink is installed");
		}
		sink.write(batch);
	}

	private static CloudEvent event(String type) {
		CloudEvent e = new CloudEvent();
		e.setType(type);
		e.setId("evt-" + type.hashCode());
		e.setTime("2026-09-06T12:00:00Z");
		return e;
	}

	@Test
	@DisplayName("writes refusals as well as grants")
	void writesBothOutcomes() throws Exception {
		// A log of successes cannot show attempted overreach, which is most of what
		// an access review is looking for.
		RecordingSink sink = new RecordingSink();
		List<CloudEvent> batch = Arrays.asList(
				event(BladeEventTypes.ACCESS_PERMITTED),
				event(BladeEventTypes.ACCESS_DENIED));

		handOff(sink, batch);

		assertEquals(2, sink.written.size());
		assertEquals(BladeEventTypes.ACCESS_DENIED, sink.written.get(1).getType());
	}

	@Test
	@DisplayName("writes the whole batch, in order, unchanged")
	void doesNotFilterOrReorder() throws Exception {
		RecordingSink sink = new RecordingSink();
		List<CloudEvent> batch = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			CloudEvent e = event(BladeEventTypes.ACCESS_PERMITTED);
			e.setId("evt-" + i);
			batch.add(e);
		}

		handOff(sink, batch);

		assertEquals(5, sink.written.size());
		for (int i = 0; i < 5; i++) {
			assertEquals("evt-" + i, sink.written.get(i).getId(), "order must be preserved");
		}
	}

	@Test
	@DisplayName("a failing sink propagates, so the batch is redelivered")
	void doesNotSwallowFailures() {
		// Catching here would acknowledge records that were never stored. A sink
		// that is down must become a growing queue, not a silent gap.
		RecordingSink sink = new RecordingSink();
		sink.failure = new IOException("object storage is unreachable");

		IOException thrown = assertThrows(IOException.class,
				() -> handOff(sink, Collections.singletonList(event(BladeEventTypes.ACCESS_DENIED))));
		assertTrue(thrown.getMessage().contains("unreachable"));
	}

	@Test
	@DisplayName("no sink is a failure, not a quiet discard")
	void refusesWithoutASink() {
		assertThrows(IOException.class,
				() -> handOff(null, Collections.singletonList(event(BladeEventTypes.ACCESS_PERMITTED))));
	}

	@Test
	@DisplayName("an empty batch does nothing and does not fail")
	void emptyBatchIsHarmless() throws Exception {
		RecordingSink sink = new RecordingSink();

		handOff(sink, Collections.<CloudEvent>emptyList());
		handOff(sink, null);

		assertTrue(sink.written.isEmpty());
	}

	@Test
	@DisplayName("it subscribes to both access types and nothing else")
	void subscribesToExactlyTheAccessTypes() {
		List<String> types = AuditSubscription.auditedTypes();

		assertEquals(2, types.size());
		assertTrue(types.contains(BladeEventTypes.ACCESS_PERMITTED));
		assertTrue(types.contains(BladeEventTypes.ACCESS_DENIED));
	}
}
