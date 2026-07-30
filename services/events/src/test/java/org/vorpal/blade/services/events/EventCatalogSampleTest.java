package org.vorpal.blade.services.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventSourceGenerator;
import org.vorpal.blade.framework.v3.events.EventSubscription;

/// The catalog a fresh install starts from, checked against the consumers this
/// repository actually ships.
///
/// **The question this file answers is the one that started the design:** can
/// several applications each receive their own copy of the same event? The
/// assertions below are that question, made mechanical — on the real sample
/// catalog rather than a fixture, so a change to either the catalog or the
/// shipped consumers has to keep the answer yes.
class EventCatalogSampleTest {

	private static final EventCatalog SAMPLE = new EventCatalogSample();

	@Test
	@DisplayName("the sample an operator starts from has nothing wrong with it")
	void theSampleValidates() {
		List<String> findings = SAMPLE.validate();
		assertTrue(findings.isEmpty(), "a fresh install should not open on a catalog that reports problems: "
				+ findings);
	}

	@Nested
	@DisplayName("two applications, one event")
	class TwoApplications {

		private EventSubscription transfer() {
			EventSubscription found = SAMPLE.findSubscription("transfer");
			assertNotNull(found, "services/transfer ships TransferEventListener; the catalog must declare it");
			return found;
		}

		private EventSubscription sink() {
			EventSubscription found = SAMPLE.findSubscription(BladeEventCatalog.ANALYTICS_SUBSCRIPTION);
			assertNotNull(found, "services/analytics ships AnalyticsEventListener");
			return found;
		}

		@Test
		@DisplayName("both subscribers cover a transfer request")
		void bothCoverTheSameEvent() {
			assertTrue(transfer().covers(BladeEventTypes.TRANSFER_REQUESTED),
					"the actor names it, so its selector narrows to it");
			assertTrue(sink().covers(BladeEventTypes.TRANSFER_REQUESTED),
					"the sink names nothing, so it takes everything — including this");
		}

		/// The defect that made subscriptions first-class. Identity used to be
		/// derived from the *event type*, so two applications consuming one event
		/// generated the same pair — one subscription named twice, with the two
		/// applications competing for a single stream.
		@Test
		@DisplayName("and their JMS identities differ, so neither consumes the other's copy")
		void identitiesDiffer() {
			assertNotEquals(transfer().clientId(), sink().clientId());
			assertNotEquals(transfer().subscriptionName(), sink().subscriptionName());
		}

		@Test
		@DisplayName("and their MDB class names differ, so backpressure in one cannot suspend the other")
		void classNamesDiffer() {
			assertNotEquals(transfer().effectiveJavaClassName(), sink().effectiveJavaClassName());
		}

		@Test
		@DisplayName("the actor filters at the broker; the sink filters in code")
		void theTwoShapesAreDifferent() {
			String selector = transfer().selector();
			assertNotNull(selector, "an actor should not wake for events it would ignore");
			assertTrue(selector.contains(BladeEventTypes.TRANSFER_REQUESTED));
			assertFalse(selector.contains(BladeEventTypes.CALL_STARTED),
					"naming a call event here would mean receiving every call on the domain");

			assertNull(sink().selector(),
					"a selector would freeze the persisted set at generation time, so a type marked "
							+ "persisted tomorrow would be silently missed");
		}
	}

	@Nested
	@DisplayName("the catalog describes files that exist")
	class Shipped {

		/// A sample catalog naming `com.example` packages nobody can open would be
		/// exactly the drift this subsystem exists to abolish — an odd place to
		/// start abolishing it.
		@Test
		@DisplayName("every subscription's generated class name matches a shipped consumer")
		void generatedNamesMatchShippedFiles() {
			String[][] shipped = {
					{ "calendar", "org.vorpal.blade.services.events.CalendarEventListener" },
					{ "transfer", "org.vorpal.blade.services.transfer.events.TransferEventListener" },
					{ BladeEventCatalog.ANALYTICS_SUBSCRIPTION,
							"org.vorpal.blade.services.analytics.jms.AnalyticsEventListener" } };

			for (String[] each : shipped) {
				EventSubscription subscription = SAMPLE.findSubscription(each[0]);
				assertNotNull(subscription, each[0]);
				String expected = subscription.getJavaPackage() + "." + subscription.effectiveJavaClassName()
						+ "Listener";
				assertEquals(each[1], expected,
						"the catalog and the file on disk must agree about where the consumer lives");
			}
		}

		@Test
		@DisplayName("every subscription generates a consumer that names its own identity")
		void everySubscriptionGenerates() {
			List<String> clientIds = new ArrayList<>();
			for (EventSubscription subscription : SAMPLE.subscriptionsOrEmpty()) {
				String mdb = EventSourceGenerator.consumerSource(subscription, SAMPLE);

				assertTrue(mdb.contains("public class " + subscription.effectiveJavaClassName()
						+ "Listener implements MessageListener"), subscription.getName());
				assertTrue(mdb.contains("propertyName = \"clientId\", propertyValue = \""
						+ subscription.clientId() + "\""), subscription.getName());

				assertFalse(clientIds.contains(subscription.clientId()),
						subscription.getName() + " shares a client id with another subscription");
				clientIds.add(subscription.clientId());
			}
			assertEquals(3, clientIds.size());
		}
	}
}
