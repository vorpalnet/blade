package org.vorpal.blade.services.transfer.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v2.transfer.api.Notification;
import org.vorpal.blade.framework.v2.transfer.api.TransferRequest;
import org.vorpal.blade.framework.v3.transfer.TransferSettings;
import org.vorpal.blade.framework.v3.transfer.Webhook;
import org.vorpal.blade.services.transfer.TransferSettingsSample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Which webhook a transfer reports to, which sockets hear it, and what they get.
class TransferNoticesTest {

	private static TransferSettings settings(String defaultWebhook) {
		TransferSettings settings = new TransferSettings();
		settings.getWebhooks().put("crm", new Webhook("http://crm.example.com/hook"));
		settings.getWebhooks().put("ops", new Webhook("http://ops.example.com/hook"));
		settings.setDefaultWebhook(defaultWebhook);
		return settings;
	}

	private static TransferRequest rest(Notification.Style style, String callbackName) {
		TransferRequest request = new TransferRequest();
		request.notification = new Notification();
		request.notification.style = style;
		request.notification.callbackName = callbackName;
		request.notification.uid = "u-1";
		request.sessionKey = "GUCID-1";
		return request;
	}

	@Test
	void aCallbackRequestGetsItsNamedWebhook() {
		TransferSettings settings = settings("ops");
		assertSame(settings.getWebhooks().get("crm"),
				TransferNotices.webhookFor(settings, rest(Notification.Style.callback, "crm")));
	}

	@Test
	void aReferGetsTheDefaultWebhook() {
		TransferSettings settings = settings("ops");
		assertSame(settings.getWebhooks().get("ops"), TransferNotices.webhookFor(settings, null));
	}

	@Test
	void aWaitedRequestGetsTheDefaultNotItsCallbackName() {
		TransferSettings settings = settings("ops");
		assertSame(settings.getWebhooks().get("ops"),
				TransferNotices.webhookFor(settings, rest(Notification.Style.async, "crm")));
	}

	@Test
	void noDefaultMeansNoWebhook() {
		assertNull(TransferNotices.webhookFor(settings(null), null));
	}

	@Test
	void anUnknownNameMeansNoWebhook() {
		assertNull(TransferNotices.webhookFor(settings(null), rest(Notification.Style.callback, "nobody")));
	}

	@Test
	void aWebhookFiltersEvents() {
		Webhook all = new Webhook("http://example.com");
		assertTrue(all.wants("transferInitiated"));
		Webhook some = new Webhook("http://example.com");
		some.setEvents(Arrays.asList("transferCompleted", "transferDeclined"));
		assertTrue(some.wants("transferCompleted"));
		assertFalse(some.wants("transferInitiated"));
	}

	private static TransferNotice notice() {
		TransferNotice notice = new TransferNotice();
		notice.event = "transferCompleted";
		notice.vorpalId = "ABCD1234";
		notice.sessionKeys = Arrays.asList("GUCID-1");
		notice.uid = "u-1";
		return notice;
	}

	@Test
	void anUnfilteredSocketHearsEverything() {
		assertTrue(new TransferEventsEndpoint.Filter(null, null).matches(notice()));
	}

	@Test
	void aSocketFiltersBySessionKeyOrVorpalId() {
		assertTrue(new TransferEventsEndpoint.Filter("GUCID-1", null).matches(notice()));
		assertTrue(new TransferEventsEndpoint.Filter("ABCD1234", null).matches(notice()));
		assertFalse(new TransferEventsEndpoint.Filter("GUCID-2", null).matches(notice()));
	}

	@Test
	void aSocketFiltersByUid() {
		assertTrue(new TransferEventsEndpoint.Filter(null, "u-1").matches(notice()));
		assertFalse(new TransferEventsEndpoint.Filter(null, "u-2").matches(notice()));
		assertFalse(new TransferEventsEndpoint.Filter("GUCID-1", "u-2").matches(notice()));
	}

	@Test
	void theNoticeLeadsWithItsEventAndOmitsNulls() throws Exception {
		JsonNode json = TransferNotices.MAPPER.readTree(TransferNotices.MAPPER.writeValueAsString(notice()));
		assertEquals("event", json.fieldNames().next());
		assertFalse(json.has("status"));
	}

	@Test
	void theSampleWebhookRoundTrips() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(new TransferSettingsSample());
		TransferSettings back = mapper.readValue(json, TransferSettings.class);
		assertEquals("https://webhooks.example.com/transfer", back.getWebhooks().get("myWebhook").getUrl());
		assertNull(back.getDefaultWebhook());
	}

	/// The WAR declares one type per event, under the application's own prefix,
	/// in the shape the framework reads at deployment.
	@Test
	void theDeclaredTypesAreTheEvents() throws Exception {
		org.vorpal.blade.framework.v3.events.EventCatalog declared = new ObjectMapper().readValue(
				new java.io.File("src/main/webapp/WEB-INF/blade-events.json"),
				org.vorpal.blade.framework.v3.events.EventCatalog.class);
		java.util.List<String> types = new java.util.ArrayList<>();
		declared.typesOrEmpty().forEach(t -> types.add(t.getType()));
		java.util.List<String> expected = new java.util.ArrayList<>();
		TransferNotices.EVENTS.forEach(e -> expected.add(TransferNotices.PREFIX + e));
		assertEquals(expected, types);
	}

	/// A relayed event's payload carries the framework's fields beside the
	/// notice's; the socket frame is the notice alone, as the webhook gets it.
	@Test
	void aRelayedPayloadReadsBackAsTheNotice() throws Exception {
		TransferNotice notice = notice();
		com.fasterxml.jackson.databind.node.ObjectNode payload = TransferNotices.MAPPER.createObjectNode();
		payload.put("appName", "transfer").put("server", "engine1").put("occurredAt", "2026-11-06T00:00:00Z");
		payload.setAll((com.fasterxml.jackson.databind.node.ObjectNode) TransferNotices.MAPPER.valueToTree(notice));
		TransferNotice back = TransferNotices.MAPPER.treeToValue(payload, TransferNotice.class);
		assertEquals(TransferNotices.MAPPER.writeValueAsString(notice), TransferNotices.MAPPER.writeValueAsString(back));
	}

	/// The five events analytics already records through the framework's own
	/// go out sessionless: no payload `vorpalId`, so analytics does not store
	/// them twice. The relay restores it from the subject.
	@Test
	void aSessionlessEventIsNotACallEventYetReadsBackWhole() throws Exception {
		TransferNotice notice = notice();
		org.vorpal.blade.framework.v3.events.CloudEvent event = new org.vorpal.blade.framework.v3.events.CloudEvent();
		com.fasterxml.jackson.databind.node.ObjectNode payload = TransferNotices.payload(notice);
		payload.remove("vorpalId");
		payload.put("appName", "transfer").put("occurredAt", "2026-11-06T00:00:00Z");
		event.setData(payload);
		event.setSubject(notice.vorpalId);
		assertFalse(event.isCallScoped());
		assertEquals(TransferNotices.MAPPER.writeValueAsString(notice),
				TransferNotices.MAPPER.writeValueAsString(TransferNoticeRelay.noticeOf(event)));
	}

	/// `consultationStarted`, which the framework does not record, stays a
	/// call event.
	@Test
	void consultationStartedIsACallEvent() {
		org.vorpal.blade.framework.v3.events.CloudEvent event = new org.vorpal.blade.framework.v3.events.CloudEvent();
		event.setData(TransferNotices.payload(notice()));
		assertTrue(event.isCallScoped());
		assertEquals("consultationStarted", TransferNotices.CALL_EVENT);
	}
}
