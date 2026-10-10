package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.events.AnalyticsEventMapper;
import org.vorpal.blade.framework.v3.events.CallScope;
import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// A call's tenant and department ride X-Vorpal-ID from the hop that set them,
/// are believed only from a trusted hop, and are stamped on every event over
/// whatever a producer wrote.
class CallScopeTest {

	@BeforeEach
	void install() {
		System.clearProperty(TrustedPeers.PROPERTY);
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		Callflow.setLogger(new CapturingLogger());
	}

	@AfterEach
	void remove() {
		System.clearProperty(TrustedPeers.PROPERTY);
		Callflow.setSipFactory(null);
		Callflow.setSipUtil(null);
		Callflow.setLogger(null);
	}

	private static DetachedRequest inviteFrom(String peer, String xVorpalId) throws Exception {
		DetachedApplicationSession appSession = new DetachedApplicationSession("test");
		DetachedRequest request = new DetachedRequest(appSession, "INVITE") {
			private static final long serialVersionUID = 1L;

			@Override
			public String getRemoteAddr() {
				return peer;
			}

			@Override
			public String getInitialRemoteAddr() {
				return null;
			}
		};
		request.setSession(new DetachedSipSession(appSession));
		request.setRoutingDirective(SipApplicationRoutingDirective.NEW, null);
		request.setHeader(Callflow.X_VORPAL_ID, xVorpalId);
		return request;
	}

	@Test
	void aTrustedHopsScopeIsAdopted() throws Exception {
		DetachedRequest request = inviteFrom("127.0.0.1", "0000C0DE;ts=18F3A2B4C10;tn=uhg;dp=radiology");
		Callflow.getVorpalSessionId(request);
		assertEquals("uhg", Callflow.getTenant(request.getApplicationSession()));
		assertEquals("radiology", Callflow.getDepartment(request.getApplicationSession()));
	}

	@Test
	void aCallerCannotClaimAScope() throws Exception {
		DetachedRequest request = inviteFrom("203.0.113.9", "0000C0DE;ts=18F3A2B4C10;tn=uhg;dp=radiology");
		Callflow.getVorpalSessionId(request);
		assertNull(Callflow.getTenant(request.getApplicationSession()));
		assertNull(Callflow.getDepartment(request.getApplicationSession()));
	}

	@Test
	void aValueThatIsNotANameIsDropped() throws Exception {
		DetachedRequest request = inviteFrom("127.0.0.1", "0000C0DE;tn=\"uhg' OR 1=1\";dp=sales");
		Callflow.getVorpalSessionId(request);
		assertNull(Callflow.getTenant(request.getApplicationSession()));
		assertEquals("sales", Callflow.getDepartment(request.getApplicationSession()));
		assertThrows(IllegalArgumentException.class,
				() -> Callflow.setTenant(request.getApplicationSession(), "two words"));
		assertFalse(Callflow.isScopeName("x".repeat(65)), "longer than the column");
	}

	@Test
	void theScopeGoesOnToTheNextHop() throws Exception {
		DetachedRequest inbound = inviteFrom("127.0.0.1", "0000C0DE;ts=18F3A2B4C10");
		Callflow.getVorpalSessionId(inbound);
		Callflow.setTenant(inbound.getApplicationSession(), "uhg");
		Callflow.setDepartment(inbound.getApplicationSession(), "sales");

		DetachedRequest outbound = new DetachedRequest(inbound.getApplicationSession(), "INVITE");
		outbound.setSession(new DetachedSipSession(inbound.getApplicationSession()));
		Callflow.stampVorpalIdHeaders(outbound, outbound.getApplicationSession(), outbound.getSession());
		Parameterable stamped = outbound.getParameterableHeader(Callflow.X_VORPAL_ID);
		assertEquals("uhg", stamped.getParameter(Callflow.TENANT_PARAM));
		assertEquals("sales", stamped.getParameter(Callflow.DEPARTMENT_PARAM));
	}

	@Test
	void eventsCarryTheScopeAtTheTimeTheyWereBuilt() throws Exception {
		DetachedRequest request = inviteFrom("127.0.0.1", "0000C0DE;ts=18F3A2B4C10;tn=uhg;dp=sales");
		Callflow.getVorpalSessionId(request);
		CallScope before = CallScope.of(request.getApplicationSession());
		Callflow.setDepartment(request.getApplicationSession(), "radiology"); // transferred
		CallScope after = CallScope.of(request.getApplicationSession());

		assertEquals("sales", data(before).get("department").asText());
		assertEquals("radiology", data(after).get("department").asText());
		assertEquals("uhg", data(after).get("tenant").asText());
	}

	@Test
	void aProducerCannotFileAnEventUnderAnotherScope() {
		ObjectNode forged = new ObjectMapper().createObjectNode().put("tenant", "bofa").put("department", "fraud");
		CloudEvent event = AnalyticsEventMapper.scoped(AnalyticsEventMapper.callScoped("/blade/test", "t", 1L, null,
				null, "app", "d", "s", null, forged), new CallScope("uhg", null));
		ObjectNode data = (ObjectNode) event.getData();
		assertEquals("uhg", data.get("tenant").asText());
		assertFalse(data.has("department"), "the call has none, and the producer's is not believed");
		assertTrue(AnalyticsEventMapper.ENVELOPE.contains("tenant"));
	}

	private static ObjectNode data(CallScope scope) {
		return (ObjectNode) AnalyticsEventMapper.scoped(AnalyticsEventMapper.callScoped("/blade/test", "t", 1L, null,
				null, "app", "d", "s", null, null), scope).getData();
	}
}
