package org.vorpal.blade.services.webrtc;

import java.util.List;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;

/// Events an application behind the gateway sends a browser mid-call, such as a meeting's
/// captions, roster and which track shows whom, taken from the event bus and pushed down the
/// browser's socket.
///
/// The application publishes a CloudEvent whose `subject` is the Vorpal-ID of the browser's call.
/// Every gateway node hears every event (the subscriber attaches to each member of the bus topic);
/// the node holding that call's browser ([BrowserCalls]) delivers it, with the subject replaced by
/// the browser's own call id so the page files it under the call, and the rest ignore it.
///
/// These travel on the bus, not in the dialog: a caption per line per participant is data, and a
/// SIP `INFO` for each would put it through the signaling path one transaction at a time
/// (RFC 6086 section 8.3: "SIP is a poor mechanism for direct exchange of bulk data").
///
/// Non-durable, on purpose: an event for a browser that has gone is nobody's. The types are the
/// gateway's `relayedEventTypes` setting, and only an application's own namespace passes
/// ([#FAR_SIDE_PREFIX]).
@WebListener
public class FarSideEvents implements ServletContextListener, EventSubscriber.Handler {

	/// This subscriber's name on the broker, and its metric key.
	static final String SUBSCRIPTION = "blade-webrtc-far-side";

	/// The event types that may reach a browser. Only an application's own namespace, never this
	/// protocol's: a far side that could send `call.ended` or `call.update` could hang up or
	/// renegotiate the browser's call from outside it.
	static final String FAR_SIDE_PREFIX = "meeting.";

	private SubscriptionRegistrar registrar;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		SubscriptionRegistrar.meter(event.getServletContext(), SUBSCRIPTION);
		registrar = SubscriptionRegistrar.start(SUBSCRIPTION, WebrtcServlet::relayedEventTypes, /* durable */ false,
				this, /* batch */ 1, EventSubscriber.DEFAULT_BATCH_MILLIS);
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		if (registrar != null) {
			registrar.stop();
		}
	}

	@Override
	public void handle(List<CloudEvent> batch) {
		for (CloudEvent event : batch) {
			BrowserCalls.Call call = BrowserCalls.find(vorpalIdOf(event.getSubject()));
			CloudEvent forBrowser = (call == null) ? null : forBrowser(event, call.callId);
			if (forBrowser != null) {
				BrowserRegistry.deliver(call.aor, forBrowser);
			}
		}
	}

	/// The Vorpal-ID a subject names: the subject itself, or the part before the `.` of the
	/// framework's `<vorpalId>.<timestamp>` form.
	static String vorpalIdOf(String subject) {
		if (subject == null) {
			return null;
		}
		int dot = subject.indexOf('.');
		return (dot > 0) ? subject.substring(0, dot) : subject;
	}

	/// `event` filed under the browser's `callId`, or null when its type is outside
	/// [#FAR_SIDE_PREFIX].
	static CloudEvent forBrowser(CloudEvent event, String callId) {
		if (event == null || event.getType() == null || !event.getType().startsWith(FAR_SIDE_PREFIX)) {
			return null;
		}
		return CloudEvent.create(event.getType(), event.getSource(), callId, event.getData());
	}
}
