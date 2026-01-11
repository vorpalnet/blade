package org.vorpal.blade.services.analytics.sip;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.LogManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v2.testing.DummyApplicationSession;
import org.vorpal.blade.framework.v2.testing.DummyRequest;
import org.vorpal.blade.services.analytics.jpa.Attribute;
import org.vorpal.blade.services.analytics.jpa.Event;

import com.bea.wcp.sip.engine.server.SipURIImpl;

public class AnalyticsB2bua extends Analytics {
	public enum B2buaEvents {
		exception, servletCreated, servletDestroyed, callStarted, callAnswered, callConnected, callCompleted,
		callDeclined, callAbandoned
	};

	public Map<B2buaEvents, EventSelector> events = new HashMap<>();

	public EventSelector createEvent(B2buaEvents event) {
		EventSelector evsel = new EventSelector();
		events.put(event, evsel);
		return evsel;
	}

	public AnalyticsB2bua() {

		events = new HashMap<>();
		this.enabled = Boolean.TRUE;
		this.jmsFactory = "jms/TestConnectionFactory";
		this.jmsQueue = "jms/TestJMSQueue";

		// add support to AttributeSelector for Exception & SipServletEvent

		EventSelector eventSelector = createEvent(B2buaEvents.callStarted);
		eventSelector.addAttribute("from", "From", "sip:(.*)@.*", "$1");
		eventSelector.addAttribute("to", "To", "sip:(.*)@.*", "$1");
		eventSelector.addAttribute("ruri", "RequestURI", "^.*$", "$0");
		eventSelector.addAttribute("ani", "From", Configuration.SIP_ADDRESS_PATTERN, "${user}");
		eventSelector.addAttribute("did", "To", Configuration.SIP_ADDRESS_PATTERN, "${user}");

	}

	public Event generateEvent(String eventName, SipServletRequest request) {

		Event event = new Event();
		event.setName(eventName);

		event.setApplicationId(SettingsManager.getAppInstanceId());

		EventSelector evsel = events.get(B2buaEvents.callStarted);

		AttributesKey attrsKey;
		for (AttributeSelector attrSel : evsel.getAttributes()) {
			Map<String, String> attributes;
			attrsKey = attrSel.findKey(request);

//			System.out.println("running... "+ (i++) //
//					+ ", attrSel.getAttribute()="+attrSel.getAttribute() //
//					+", attrSel.getDescription()="+attrSel.getDescription() //
//					+", attrSel.getExpression()="+attrSel.getExpression() //
//					+", attrSel.getId()="+attrSel.getId() //
//					+", attrSel.getPattern()="+attrSel.getPattern() //
//					+", attrsKey.key="+ attrsKey.key //
//					+", attrsKey.attributes="+ attrsKey.attributes //
//					);

			if (attrsKey != null) {
				event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
			}

		}

		return event;
	}

	public static void main(String[] args) throws Exception {
		SipApplicationSession appSession = new DummyApplicationSession("analytics");

		SipServletRequest request = new DummyRequest(appSession, "INVITE", "sip:alice@vorpal.org",
				"sip:bob@vorpal.org");
		request.setRequestURI(new SipURIImpl("sip:192.168.1.1", true));

		Logger sipLogger = LogManager.getLogger("BLADE");
		Callflow.setSipLogger(sipLogger);
		SettingsManager.setSipLogger(sipLogger);

		sipLogger.removeHandler(sipLogger.getHandlers()[0]);
		ConsoleHandler consoleHandler = new ConsoleHandler();

		SimpleFormatter formatter = new SimpleFormatter();
		consoleHandler.setFormatter(formatter);
		consoleHandler.setLevel(Level.ALL);

		sipLogger.addHandler(consoleHandler);
		sipLogger.setLevel(Level.ALL);
		sipLogger.setUseParentHandlers(false);

		sipLogger.info(request, "Is sipLogger null? " + (sipLogger == null));
		AnalyticsB2bua config = new AnalyticsB2bua();

		Event event = config.generateEvent("callStarted", request);

		System.out.println(Logger.serializeObject(event));

	}

}
