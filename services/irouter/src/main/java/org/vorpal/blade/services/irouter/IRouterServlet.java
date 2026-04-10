package org.vorpal.blade.services.irouter;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;

/// Intelligent Router — a B2BUA-based SIP routing service.
///
/// The irouter uses the BLADE v3 configuration framework to make
/// routing decisions for inbound SIP calls. It operates as a
/// Back-to-Back User Agent (B2BUA), giving full control over both
/// call legs — unlike the proxy-based `proxy-router`, the irouter
/// stays in the dialog path for the full call lifetime.
///
/// ## Routing flow
///
/// When an INVITE arrives, `callStarted()` is called with the
/// outbound request:
///
/// 1. [Selector]s extract a routing key and session attributes from
///    the inbound SIP headers (To, From, custom headers, etc.)
/// 2. Local [TranslationTable]s (hash and prefix) are searched for
///    a matching [RoutingTreatment]
/// 3. If no local match, [Resolver]s query external systems (REST
///    APIs, and in the future JDBC/LDAP) using the extracted key
///    and session attributes for `${var}` substitution
/// 4. The matched [RoutingTreatment] provides the destination URI
///    and optional custom headers, applied to the outbound INVITE
/// 5. If nothing matches, the call is rejected with 404
///
/// ## Configuration
///
/// The irouter reads its configuration from the BLADE config directory:
///
/// - Config file: `<domain>/config/custom/vorpal/irouter.json`
/// - Schema file: `<domain>/config/custom/vorpal/_schemas/irouter.jschema`
/// - Sample file: `<domain>/config/custom/vorpal/_samples/irouter.json.SAMPLE`
/// - REST templates: `<domain>/config/custom/vorpal/_templates/*.txt`
///
/// ## Why B2BUA instead of Proxy?
///
/// - Full header control on both legs (add, modify, remove)
/// - SDP manipulation (codec filtering, hold, recording integration)
/// - Mid-call event handling (re-INVITE, UPDATE, INFO)
/// - Session attribute access throughout the call lifetime
/// - Integration point for REST/JDBC/LDAP lookups before routing
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class IRouterServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<IRouterConfig> settings;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settings = new SettingsManager<>(event, IRouterConfig.class, new IRouterConfigSample());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		settings.unregister();
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		IRouterConfig config = settings.getCurrent();

		Translation<RoutingTreatment> translation = config.findTranslation(outboundRequest);

		if (translation != null && translation.getTreatment() != null) {
			RoutingTreatment treatment = translation.getTreatment();

			// Apply destination URI
			if (treatment.requestUri != null) {
				URI destination = sipFactory.createURI(treatment.requestUri);
				Callflow.copyParameters(outboundRequest.getRequestURI(), destination);
				outboundRequest.setRequestURI(destination);
				sipLogger.fine(outboundRequest, "irouter: routed to " + destination);
			}

			// Apply custom headers
			if (treatment.headers != null) {
				for (Map.Entry<String, String> entry : treatment.headers.entrySet()) {
					outboundRequest.setHeader(entry.getKey(), entry.getValue());
				}
			}

		} else {
			sipLogger.warning(outboundRequest, "irouter: no route found, rejecting with 404");
			this.doNotProcess(outboundRequest, 404);
		}
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		// pass through
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		// pass through
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		// pass through
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		// pass through
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		// pass through
	}
}
