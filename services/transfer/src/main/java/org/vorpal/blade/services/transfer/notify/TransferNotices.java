package org.vorpal.blade.services.transfer.notify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.transfer.api.Notification;
import org.vorpal.blade.framework.v2.transfer.api.TransferAPI;
import org.vorpal.blade.framework.v2.transfer.api.TransferRequest;
import org.vorpal.blade.framework.v3.events.Events;
import org.vorpal.blade.framework.v3.transfer.TransferSettings;
import org.vorpal.blade.framework.v3.transfer.Webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Sends each transfer event to whoever asked for it, as a [TransferNotice]:
/// the transfer's webhook, and the [TransferEventsEndpoint] sockets.
///
/// **One call per event, from the transfer listeners.** The transfer
/// callflows report every event twice, side by side: on the event bus, and to
/// their listener (`TransferServlet` for a REFER, `TransferRestAPI` for a
/// REST request). The listeners call [#publish], so a webhook, a socket and
/// the bus can never disagree about what happened or when. The one report
/// the bus does not get is an attended transfer's waited REST answer, which
/// comes when the target answers; see `TransferRestAPI`.
///
/// Which webhook: the REST request's, when its `notification.style` is
/// `callback`; otherwise the configuration's `defaultWebhook`, if any.
///
/// **The bus:** each notice is also this application's own event,
/// `org.vorpal.blade.services.transfer.<event>`, declared in the WAR's
/// `WEB-INF/blade-events.json`. Every engine's [TransferNoticeRelay] hears
/// them and delivers them to its own sockets, this one's included. With no
/// bus to publish on, this engine's sockets get the notice directly.
///
/// **Only `consultationStarted` is a call event.** Analytics stores every
/// call-scoped event and names a row by its type's last segment, so the
/// other five, published call-scoped, would each have been stored a second
/// time under the very name the framework's own transfer event is stored
/// as (`transferCompleted` from `org.vorpal.blade.transfer.completed`), and
/// every report counting transfers would count each one twice. They go out
/// sessionless instead, so analytics leaves them alone. Their payload
/// carries no `vorpalId`, because a payload `vorpalId` is what makes an
/// event a call event (`CloudEvent.isCallScoped`); the call's Vorpal-ID is
/// the CloudEvents `subject`, and [TransferNoticeRelay] puts it back, so a
/// socket gets the same notice as the webhook. `consultationStarted` is a fact the
/// framework does not record, so analytics stores it against the call.
public final class TransferNotices {

	/// This application's own event types: [#PREFIX] plus the event name.
	public static final String PREFIX = "org.vorpal.blade.services.transfer.";

	/// The events, in the order a transfer can produce them. All but
	/// `consultationStarted` are also the framework's own transfer events,
	/// published at the same moments; that one is an attended transfer's
	/// target answering.
	public static final List<String> EVENTS = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			"transferRequested", "transferInitiated", "consultationStarted", "transferCompleted",
			"transferDeclined", "transferAbandoned"));

	/// The one event published call-scoped, so analytics stores it; see the
	/// class description.
	static final String CALL_EVENT = "consultationStarted";

	/// Revision of the declared payload.
	static final int VERSION = 1;

	/// Written by `TransferAPI` on the application session: the REST request
	/// behind the transfer in progress.
	private static final String TXFER_REQUEST = "TXFER_REQUEST";
	/// Written by the transfer callflows: the target's URI.
	private static final String REFER_TO = "Refer-To";
	/// Written by `TransferInitialInvite`: the INVITE that started the call.
	private static final String INITIAL_INVITE = "INITIAL_INVITE";

	static final ObjectMapper MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
			.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private TransferNotices() {
	}

	/// The notice as bus fields.
	static ObjectNode payload(TransferNotice notice) {
		return (ObjectNode) MAPPER.valueToTree(notice);
	}

	/// Reports `event` (a bus event name such as `transferCompleted`) for the
	/// transfer `message` belongs to. Never throws: a notification must not
	/// break the call it describes.
	public static void publish(String event, SipServletMessage message) {
		try {
			TransferNotice notice = notice(event, message);
			String json = MAPPER.writeValueAsString(notice);

			Webhook webhook = webhookFor(settings(), notice.request);
			if (webhook != null && webhook.wants(event)) {
				WebhookSender.send(webhook, json);
			}

			boolean onBus;
			if (CALL_EVENT.equals(event)) {
				onBus = Events.publish(message.getApplicationSession(), PREFIX + event, VERSION,
						fields -> fields.setAll(payload(notice)));
			} else {
				onBus = Events.publish(PREFIX + event, VERSION, notice.vorpalId,
						fields -> {
							fields.setAll(payload(notice));
							fields.remove("vorpalId");
						});
			}
			if (!onBus) {
				TransferEventsEndpoint.deliver(notice, json);
			}
		} catch (Exception e) {
			if (SettingsManager.getSipLogger() != null) {
				SettingsManager.getSipLogger().warning(message, "TransferNotices - " + event + " not sent: " + e);
			}
		}
	}

	static TransferNotice notice(String event, SipServletMessage message) {
		SipApplicationSession appSession = message.getApplicationSession();
		TransferNotice notice = new TransferNotice();
		notice.event = event;
		notice.time = Instant.now().toString();
		notice.vorpalId = Callflow.getVorpalSessionId(appSession);
		notice.sessionKeys = sessionKeys(appSession);

		TransferRequest request = (TransferRequest) appSession.getAttribute(TXFER_REQUEST);
		if (request != null) {
			notice.request = request;
			notice.style = (request.style != null) ? request.style.toString() : null;
			notice.uid = (request.notification != null) ? request.notification.uid : null;
		}

		Object referTo = appSession.getAttribute(REFER_TO);
		if (referTo != null) {
			notice.referTo = referTo.toString();
		}

		if (message instanceof SipServletResponse) {
			SipServletResponse response = (SipServletResponse) message;
			notice.method = response.getMethod();
			notice.status = response.getStatus();
			notice.description = response.getReasonPhrase();
		} else if (message instanceof SipServletRequest) {
			notice.method = ((SipServletRequest) message).getMethod();
		}
		return notice;
	}

	/// The webhook for a transfer: its REST request's, when that asks for a
	/// callback by a configured name, else the configuration's default.
	static Webhook webhookFor(TransferSettings settings, TransferRequest request) {
		if (settings == null || settings.getWebhooks().isEmpty()) {
			return null;
		}
		String name = settings.getDefaultWebhook();
		if (request != null && request.notification != null
				&& request.notification.style == Notification.Style.callback
				&& request.notification.callbackName != null) {
			name = request.notification.callbackName;
		}
		Webhook webhook = (name != null) ? settings.getWebhooks().get(name) : null;
		if (name != null && webhook == null && SettingsManager.getSipLogger() != null) {
			SettingsManager.getSipLogger().warning("TransferNotices - no webhook named '" + name + "' is configured");
		}
		return webhook;
	}

	/// The call's session keys, recomputed from its first INVITE by the same
	/// selectors that indexed it, since the container does not hand back a
	/// session's index keys.
	private static List<String> sessionKeys(SipApplicationSession appSession) {
		Object initial = appSession.getAttribute(INITIAL_INVITE);
		TransferSettings settings = settings();
		if (!(initial instanceof SipServletRequest) || settings == null || settings.getSession() == null) {
			return null;
		}
		List<String> keys = new ArrayList<>();
		for (AttributeSelector selector : settings.getSession().getSessionSelectors()) {
			try {
				AttributesKey key = selector.findKey((SipServletRequest) initial);
				if (key != null && key.key != null && !keys.contains(key.key)) {
					keys.add(key.key);
				}
			} catch (Exception e) {
				// a selector that cannot read this INVITE adds no key
			}
		}
		return keys.isEmpty() ? null : keys;
	}

	private static TransferSettings settings() {
		Object current = (TransferAPI.getSettings() != null) ? TransferAPI.getSettings().getCurrent() : null;
		return (current instanceof TransferSettings) ? (TransferSettings) current : null;
	}
}
