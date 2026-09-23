package org.vorpal.blade.applications.agent;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;

/// The console's ear on the bus: what makes a pop change after it appears.
///
/// A card is built once from the INVITE, at ring time. Everything learned about
/// the call after that — the deepfake risk verdict, which needs seconds of audio
/// the INVITE does not have, and what the caller is saying — arrives as a bus
/// event keyed by the call's Vorpal-ID. This subscription receives those and
/// [ConsoleUpdater] turns each into an update pushed to the console holding
/// that call, over the WebSocket it already has open.
///
/// Subscribes to four types, by their first-class names (the generic call
/// event carries the post-call review under its own name, `callReviewed`) (a precise broker
/// selector; the contract is blade's, see [BladeEventTypes#CALL_RISK_ASSESSED]
/// and [BladeEventTypes#CALL_UTTERANCE]). Whoever hears the audio publishes
/// them — the agent app depends on blade alone.
///
/// **Non-durable, on purpose.** A durable subscription would hold verdicts while
/// no console was open and replay them later, to nobody: a risk score for a call
/// that ended an hour ago is not an update, it is stale. Only a live console
/// matters, so the subscription lives and dies with the app. Batch of one: an
/// update is an act on one screen, not rows to write.
///
/// The same pattern as `proto/catalog` and `proto/audit`, registered as a
/// `@WebListener` rather than in web.xml. Not the SIP servlet's lifecycle: a
/// JMS subscription pairs with `contextDestroyed`, and the registrar tolerates
/// starting before the SIP servlet has (it retries the bus on a watchdog).
@WebListener
public class ConsoleSubscription implements ServletContextListener {

	/// This subscriber's name on the broker, and its metric key.
	static final String SUBSCRIPTION = "blade-agent-console";

	static List<String> types() {
		return Arrays.asList(BladeEventTypes.CALL_RISK_ASSESSED, BladeEventTypes.CALL_RISK_FLAGGED,
				BladeEventTypes.CALL_UTTERANCE, BladeEventTypes.CALL_EVENT);
	}

	private final ConsoleUpdater handler = new ConsoleUpdater();
	private SubscriptionRegistrar registrar;
	/// The console keep-alive ([AgentConsoleRegistry#ping]); lives and dies with
	/// the app like the subscription, which is why it is started here.
	private ScheduledExecutorService keepalive;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		SubscriptionRegistrar.meter(event.getServletContext(), SUBSCRIPTION);
		registrar = SubscriptionRegistrar.start(SUBSCRIPTION, ConsoleSubscription::types, /* durable */ false, handler,
				/* batch */ 1, EventSubscriber.DEFAULT_BATCH_MILLIS);
		keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "agent-console-ping");
			t.setDaemon(true);
			return t;
		});
		keepalive.scheduleAtFixedRate(AgentConsoleRegistry::ping, AgentConsoleRegistry.PING_SECONDS,
				AgentConsoleRegistry.PING_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		if (keepalive != null) {
			keepalive.shutdownNow();
		}
		if (registrar != null) {
			registrar.stop();
		}
	}
}
