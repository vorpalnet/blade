package org.vorpal.blade.services.listener;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;
import com.fasterxml.jackson.databind.JsonNode;

/// Requests to bring another party into a live call, from the event bus.
///
/// The request names the call by its Vorpal-ID and says whom to dial. Every
/// node running the listener hears it, and the one holding the call's media
/// acts; the rest find no such call and do nothing. That is what lets the
/// screen that asks, the agent console, know nothing about where a call's media
/// lives.
///
/// A destination is dialled only when it matches one of the listener's
/// `partyTargets`. Anything else is refused and logged: a request from a screen
/// must not be able to dial the world.
@WebListener
public class PartyRequests implements ServletContextListener {

	static final String SUBSCRIPTION = "blade-listener-party";

	private SubscriptionRegistrar registrar;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		SubscriptionRegistrar.meter(event.getServletContext(), SUBSCRIPTION);
		registrar = SubscriptionRegistrar.start(SUBSCRIPTION,
				() -> Collections.singletonList(BladeEventTypes.CALL_PARTY_REQUESTED), /* durable */ false,
				PartyRequests::handle, /* batch */ 1, EventSubscriber.DEFAULT_BATCH_MILLIS);
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		if (registrar != null) {
			registrar.stop();
		}
	}

	static void handle(List<CloudEvent> events) {
		for (CloudEvent event : events) {
			try {
				handle(event);
			} catch (Throwable t) {
				Callflow.getSipLogger().warning("PartyRequests: a request could not be handled: " + t);
			}
		}
	}

	private static void handle(CloudEvent event) throws Exception {
		JsonNode data = event.getData();
		String vorpalId = (data == null) ? null : data.path("vorpalId").asText(null);
		Map.Entry<String, ListenerAnchor.Anchor> held = ListenerAnchor.byVorpalId(vorpalId);
		if (held == null) {
			return; // another node's call, or no call
		}
		String target = attribute(data, "target");
		String label = attribute(data, "label");
		ListenerSettings cfg = (ListenerServlet.settings == null) ? null : ListenerServlet.settings.getCurrent();
		if (target == null || cfg == null || !allowed(cfg.getPartyTargets(), target)) {
			Callflow.getSipLogger().warning("PartyRequests: refused to bring " + target + " into " + vorpalId
					+ " (requested by " + attribute(data, "requestedBy") + "): not in partyTargets");
			return;
		}
		String name = (label == null || label.isEmpty()) ? "guest" : label;
		SipApplicationSession app = Callflow.getSipUtil().getApplicationSessionById(held.getKey());
		if (!(app instanceof WlssSipApplicationSession) || !app.isValid()) {
			return;
		}
		((WlssSipApplicationSession) app).doAction(new WlssAction() {
			@Override
			public Object run() throws Exception {
				new ListenerAnchor().addParty(app, held.getValue(), target, name);
				return null;
			}
		});
	}

	/// True when `target` matches one of `patterns` in full. Empty allows nothing.
	static boolean allowed(List<String> patterns, String target) {
		if (patterns == null || target == null) {
			return false;
		}
		for (String pattern : patterns) {
			try {
				if (pattern != null && target.matches(pattern)) {
					return true;
				}
			} catch (java.util.regex.PatternSyntaxException e) {
				Callflow.getSipLogger().warning("PartyRequests: ignoring the bad pattern '" + pattern + "'");
			}
		}
		return false;
	}

	/// One attribute of a call-scoped event, from `data.attributes`.
	private static String attribute(JsonNode data, String name) {
		JsonNode list = (data == null) ? null : data.path("attributes");
		if (list != null && list.isArray()) {
			for (JsonNode a : list) {
				if (name.equals(a.path("name").asText(null))) {
					return a.path("value").asText(null);
				}
			}
		} else if (list != null && list.isObject() && list.has(name)) {
			return list.path(name).asText(null);
		}
		return null;
	}
}
