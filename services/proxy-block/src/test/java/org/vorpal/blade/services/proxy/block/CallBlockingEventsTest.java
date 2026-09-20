package org.vorpal.blade.services.proxy.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.sip.SipSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.sip.DetachedSipURI;
import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;
import org.vorpal.blade.framework.v3.irouter.IRouterInvite;

/// A declined call publishes `callDeclined` carrying the verdict header, using
/// the sample's own analytics definitions and the router's real response path.
class CallBlockingEventsTest {

	private final List<AnalyticsEvent> events = new ArrayList<>();

	/// Exposes the router's protected response path.
	static class Probe extends IRouterInvite {
		private static final long serialVersionUID = 1L;

		Probe(IRouterConfig config) {
			super(config);
		}

		void respond(DetachedRequest request, Route route) throws Exception {
			sendStatus(request, route, new MemoryContext());
		}
	}

	@BeforeEach
	void install() {
		CapturingLogger logger = new CapturingLogger() {
			@Override
			public void logEvent(SipSession session, AnalyticsEvent event) {
				events.add(event);
			}

			// The analytics log level also turns event collection on, so a
			// logger that logs everything would hide a disabled switch.
			@Override
			public boolean isLoggable(Level level) {
				return false;
			}
		};
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		Callflow.setLogger(logger);
		SettingsManager.setSipLogger(logger);

		Analytics analytics = new CallBlockingConfigSample().getAnalytics();
		analytics.setEnabled(true);
		SettingsManager.setAnalytics(analytics);
	}

	@AfterEach
	void remove() {
		SettingsManager.setAnalytics(null);
		SettingsManager.setSipLogger(null);
		Callflow.setLogger(null);
		Callflow.setSipUtil(null);
		Callflow.setSipFactory(null);
	}

	private static DetachedRequest invite() throws Exception {
		DetachedApplicationSession appSession = new DetachedApplicationSession("block");
		DetachedRequest request = new DetachedRequest(appSession, "INVITE");
		request.setSession(new DetachedSipSession(appSession));
		request.setRequestURI(new DetachedSipURI("sip:+18005550199@sbc.example.com"));
		request.setHeader("From", "<sip:+12025550150@carrier.example.com>;tag=1");
		request.setHeader("To", "<sip:+18005550199@sbc.example.com>");
		return request;
	}

	@Test
	void declinePublishesVerdict() throws Exception {
		Route blocked = new Route(603, "Decline").addHeader("X-Call-Screen", "block;reason=block-list");

		new Probe(new CallBlockingConfigSample()).respond(invite(), blocked);

		assertEquals(1, events.size());
		AnalyticsEvent event = events.get(0);
		assertEquals(IRouterInvite.EVENT_DECLINED, event.getName());
		assertEquals("block;reason=block-list", event.getAttributes().get("screen"));
		assertEquals("+12025550150", event.getAttributes().get("caller"));
		assertEquals("+18005550199", event.getAttributes().get("dialed"));
	}

	@Test
	void disabledAnalyticsPublishesNothing() throws Exception {
		SettingsManager.getAnalytics().setEnabled(false);
		Route blocked = new Route(603, "Decline").addHeader("X-Call-Screen", "block;reason=own-number");

		new Probe(new CallBlockingConfigSample()).respond(invite(), blocked);

		assertEquals(0, events.size());
		assertNotNull(SettingsManager.getAnalytics());
	}
}
