package org.vorpal.blade.services.transfer.notify;

import java.util.List;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;


/// Takes this application's transfer events ([TransferNotices#EVENTS]) off
/// the event bus and hands each, as a [TransferNotice], to this
/// engine's [TransferEventsEndpoint] sockets. Every engine runs one, and
/// every one hears every notice (a live subscription attaches to each member
/// of the topic), so a client sees a call's transfer whichever engine it is
/// connected to and whichever engine handles the call.
///
/// Live, not durable: a notice for a socket that has closed is nobody's, and
/// a replay after a restart would show a client transfers long over.
@WebListener
public class TransferNoticeRelay implements ServletContextListener, EventSubscriber.Handler {

	/// This subscriber's name on the broker, and its metric key.
	static final String SUBSCRIPTION = "blade-transfer-notices";

	private SubscriptionRegistrar registrar;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		List<String> types = new java.util.ArrayList<>();
		for (String name : TransferNotices.EVENTS) {
			types.add(TransferNotices.PREFIX + name);
		}
		registrar = SubscriptionRegistrar.named(SUBSCRIPTION).types(types).live()
				.start(event.getServletContext(), this);
		TransferEventsEndpoint.startKeepalive();
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		TransferEventsEndpoint.stopKeepalive();
		if (registrar != null) {
			registrar.stop();
		}
	}

	/// The notice a bus event carries. The payload is the notice's own fields
	/// beside the framework's (`appName`, `server`, `occurredAt`, ...); an
	/// event published sessionless has its Vorpal-ID as the subject instead.
	static TransferNotice noticeOf(CloudEvent event) throws java.io.IOException {
		TransferNotice notice = TransferNotices.MAPPER.treeToValue(event.getData(), TransferNotice.class);
		if (notice.vorpalId == null) {
			notice.vorpalId = event.getSubject();
		}
		return notice;
	}

	@Override
	public void handle(List<CloudEvent> batch) {
		for (CloudEvent event : batch) {
			if (event.getType() == null || !event.getType().startsWith(TransferNotices.PREFIX)
					|| event.getData() == null) {
				continue;
			}
			try {
				TransferNotice notice = noticeOf(event);
				TransferEventsEndpoint.deliver(notice, TransferNotices.MAPPER.writeValueAsString(notice));
			} catch (Exception e) {
				if (SettingsManager.getSipLogger() != null) {
					SettingsManager.getSipLogger().warning("TransferNoticeRelay - unreadable notice: " + e);
				}
			}
		}
	}
}
