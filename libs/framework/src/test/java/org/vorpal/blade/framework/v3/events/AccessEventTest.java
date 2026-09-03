package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.security.AccessDecision;
import org.vorpal.blade.framework.v3.security.DataPermission;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

import com.fasterxml.jackson.databind.JsonNode;

/// Offline coverage for the access-audit record.
///
/// Three properties are what make this an audit log rather than a log, and each
/// is a test here: a refusal is published as loudly as a grant, the actor is
/// always named, and the envelope has nowhere to put the content it is
/// recording access to.
class AccessEventTest {

	private static final SubjectAttributes ALICE = SubjectAttributes.of("alice",
			Collections.singleton("qa-reviewers"));

	@Nested
	@DisplayName("publishes both ways")
	class BothWays {

		@Test
		@DisplayName("a grant is a permitted event carrying the rule that allowed it")
		void permit() {
			AccessEvent event = new AccessEvent(ALICE,
					AccessDecision.permit(DataPermission.PLAY, "reviewers may listen"), "recording", "r-1001");

			assertTrue(event.isAllowed());
			assertEquals(BladeEventTypes.ACCESS_PERMITTED, event.type());
			assertEquals("permit", event.getDecision());
			assertEquals("reviewers may listen", event.getRule());
		}

		@Test
		@DisplayName("a refusal is published too, and says what was attempted and why not")
		void deny() {
			AccessEvent event = new AccessEvent(ALICE,
					AccessDecision.deny(DataPermission.EXPORT, "no rule grants phi:export to this caller"),
					"recording", "r-1001");

			assertFalse(event.isAllowed());
			assertEquals(BladeEventTypes.ACCESS_DENIED, event.type());
			assertEquals("deny", event.getDecision());
			assertEquals("phi:export", event.getAction());
			assertNull(event.getRule());
			assertNotNull(event.getReason());
		}

		@Test
		@DisplayName("break-glass is a grant with its own decision value, not a third event type")
		void breakGlass() {
			AccessEvent event = new AccessEvent(ALICE,
					AccessDecision.breakGlass(DataPermission.PLAY, "on-call (break-glass: ambulance en route)"),
					"recording", "r-1001");

			// A subscriber counting grants must not have to know the special case.
			assertEquals(BladeEventTypes.ACCESS_PERMITTED, event.type());
			// A subscriber alarming on emergency access selects on the field.
			assertEquals("breakglass", event.getDecision());
		}
	}

	@Nested
	@DisplayName("the envelope")
	class Envelope {

		@Test
		@DisplayName("names the actor, and never masks them")
		void namesTheActor() {
			JsonNode data = new AccessEvent(ALICE, AccessDecision.permit(DataPermission.LIST, "reviewers"),
					"recording", "r-1001").toCloudEvent("/blade/recordings").getData();

			assertEquals("alice", data.get("actor").asText());
		}

		@Test
		@DisplayName("has nowhere to put the content it records access to")
		void carriesNoContent() {
			JsonNode data = new AccessEvent(ALICE, AccessDecision.permit(DataPermission.TRANSCRIPT, "reviewers"),
					"transcript", "r-1001").from("10.1.1.7").toCloudEvent("/blade/recordings").getData();

			// The identifier, never the thing. An audit record that quoted the
			// transcript would disclose it to every reader of the audit log,
			// including the ones who were denied it.
			assertEquals("r-1001", data.get("resourceId").asText());
			for (String forbidden : new String[] { "text", "transcript", "audio", "content", "body", "payload",
					"data" }) {
				assertFalse(data.has(forbidden), "the access record must not carry a '" + forbidden + "' field");
			}
		}

		@Test
		@DisplayName("is not call-scoped: an access record belongs to the actor's stream, not the call's")
		void noSubject() {
			CloudEvent envelope = new AccessEvent(ALICE, AccessDecision.permit(DataPermission.PLAY, "reviewers"),
					"recording", "r-1001").toCloudEvent("/blade/recordings");
			assertNull(envelope.getSubject());
		}

		@Test
		@DisplayName("carries the declared dataversion, like every other framework-published type")
		void versioned() {
			CloudEvent envelope = new AccessEvent(ALICE, AccessDecision.permit(DataPermission.PLAY, "reviewers"),
					"recording", "r-1001").toCloudEvent("/blade/recordings");
			assertNotNull(BladeEventCatalog.versionOf(BladeEventTypes.ACCESS_PERMITTED));
			assertEquals(BladeEventCatalog.versionOf(BladeEventTypes.ACCESS_PERMITTED), envelope.getDataversion());
		}
	}

	@Nested
	@DisplayName("catalog declarations")
	class Catalog {

		@Test
		@DisplayName("access types stay off the analytics subscription")
		void notAnalytics() {
			for (EventType declaration : BladeEventCatalog.analyticsTypes()) {
				assertFalse(BladeEventTypes.ACCESS_PERMITTED.equals(declaration.getType()));
				assertFalse(BladeEventTypes.ACCESS_DENIED.equals(declaration.getType()));
			}
			// ...and are not marked for the analytics database's retention.
			for (EventType declaration : BladeEventCatalog.accessTypes()) {
				assertFalse(declaration.isPersist(), declaration.getType() + " must not be persisted by analytics");
			}
		}

		@Test
		@DisplayName("nothing in an access record is a masked field: the actor's name is the record")
		void nothingMasked() {
			for (EventType declaration : BladeEventCatalog.accessTypes()) {
				assertTrue(declaration.sensitiveFieldsOrEmpty().isEmpty(),
						declaration.getType() + " must not mask any field");
			}
		}
	}
}
