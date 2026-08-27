package org.vorpal.blade.framework.v3.events;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

/// Implements [EventBusControlMXBean] and registers it.
///
/// One instance per application that installs it — the object name carries the
/// application's name, because the bus a given application can reach is that
/// application's business: it has its own publisher, its own subscriptions and
/// its own classloader. A single domain-wide bean would answer for whichever
/// application happened to register first.
public final class EventBusControl implements EventBusControlMXBean {

	/// The CloudEvents type [#selfTest] publishes.
	///
	/// Deliberately outside the `org.vorpal.blade.*` space that the catalog
	/// declares, so no real subscription selects for it and the analytics sink
	/// does not record it. A self-test that leaves rows behind is one nobody
	/// runs on a live system.
	public static final String SELF_TEST_TYPE = "org.vorpal.blade.selftest.ping";

	/// How long [#selfTest] waits for its own event to come back.
	private static final long SELF_TEST_TIMEOUT_MS = 5_000L;

	private final String applicationName;
	private ObjectName objectName;

	private EventBusControl(String applicationName) {
		this.applicationName = applicationName;
	}

	/// Register the control bean for one application. Never fatal: an
	/// application that cannot expose its control surface must still run.
	///
	/// @return the registered control, or null if registration failed
	public static EventBusControl register(String applicationName) {
		EventBusControl control = new EventBusControl(applicationName);
		try {
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			control.objectName = new ObjectName(
					"vorpal.blade:Type=EventBus,Name=" + ObjectName.quote(String.valueOf(applicationName)));
			if (server.isRegistered(control.objectName)) {
				server.unregisterMBean(control.objectName);
			}
			server.registerMBean(new StandardMBean(control, EventBusControlMXBean.class, true),
					control.objectName);
			return control;
		} catch (Throwable t) {
			return null;
		}
	}

	/// Remove the control bean. Called at application shutdown so a redeploy
	/// does not leave a bean answering for a classloader that is gone.
	public void unregister() {
		try {
			if (objectName != null) {
				ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
			}
		} catch (Throwable t) {
			// Shutting down regardless.
		} finally {
			objectName = null;
		}
	}

	@Override
	public String getStatus() {
		StringBuilder status = new StringBuilder(256);
		status.append("application: ").append(applicationName).append('\n');

		List<String> destinations = new ArrayList<>(EventBus.registeredDestinations());
		java.util.Collections.sort(destinations);
		status.append("publishing to: ")
				.append(destinations.isEmpty() ? "NOTHING — this application publishes no events"
						: String.join(", ", destinations))
				.append('\n');
		status.append("default destination: ").append(EventBus.getDefaultDestinationJndi()).append('\n');

		List<String> names = new ArrayList<>(EventBus.registeredSubscriptions());
		java.util.Collections.sort(names);
		if (names.isEmpty()) {
			status.append("subscriptions: none\n");
			return status.toString();
		}
		status.append("subscriptions:\n");
		for (String name : names) {
			EventSubscriber subscriber = EventBus.subscriberFor(name);
			if (subscriber == null) {
				continue;
			}
			status.append("  ").append(name)
					.append(" durable=").append(subscriber.isDurable())
					.append(" paused=").append(subscriber.isPaused())
					.append(" consumers=").append(subscriber.getConsumerCount());
			if (subscriber.getConsumerCount() == 0) {
				// The line an operator is looking for. A subscription with no
				// consumer is established and receiving nothing, which is the
				// state that reads as healthy from every other angle.
				status.append("  <-- RECEIVING NOTHING");
			}
			status.append('\n');
			status.append("      selector: ")
					.append(subscriber.getSelector() == null ? "(none — takes everything)"
							: subscriber.getSelector())
					.append('\n');
		}
		return status.toString();
	}

	@Override
	public String selfTest() {
		if (EventBus.publisherFor(null) == null) {
			return "FAIL: no publisher is installed on this node, so nothing this application "
					+ "produces reaches the bus. Check the application's \"events\" config block "
					+ "and that " + EventBus.CONNECTION_FACTORY_JNDI + " is provisioned.";
		}

		String id = UUID.randomUUID().toString();
		String destination = EventBus.getDefaultDestinationJndi();
		CountDownLatch arrived = new CountDownLatch(1);
		EventSubscriber probe = null;

		try {
			// A subscription of its own, non-durable and selecting only this
			// one event, so the test neither disturbs a real consumer nor
			// leaves anything registered with the broker behind it.
			probe = new EventSubscriber(EventBus.CONNECTION_FACTORY_JNDI, destination,
					"selftest-" + id,
					EventPublisher.PROP_ID + " = '" + id + "'",
					false,
					batch -> arrived.countDown(),
					1, 50L);
			probe.init();

			CloudEvent ping = new CloudEvent();
			ping.setType(SELF_TEST_TYPE);
			ping.setId(id);
			ping.setSource("//blade/" + applicationName + "/selftest");
			EventBus.publish(ping);

			if (arrived.await(SELF_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				return "OK: published to " + destination + " and received it back (id " + id + ").";
			}
			return "FAIL: published to " + destination + " but it did not come back within "
					+ SELF_TEST_TIMEOUT_MS + "ms. The send succeeded, so the destination exists — "
					+ "check whether it is at its quota, and whether this node can consume from it.";

		} catch (Throwable t) {
			return "FAIL: " + t;
		} finally {
			if (probe != null) {
				probe.close();
			}
		}
	}
}
