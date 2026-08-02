package org.vorpal.blade.services.options;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

public class OptionsCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			SettingsManager<OptionsSettings> manager = OptionsSipServlet.settingsManager;
			OptionsSettings settings = (manager != null) ? manager.getCurrent() : null;
			if (settings == null) {
				settings = new OptionsSettingsSample();
			}

			// Administrative drain: the operator took this node out of rotation
			// via the Drain MBean (runtime state, not config — see DrainControl).
			// Checked BEFORE the overload signal: explicit intent outranks
			// automatic protection. The "Draining" reason phrase distinguishes
			// this 503 from the overload one in a trace; load balancers treat
			// them the same.
			DrainControl drain = OptionsSipServlet.drainControl;
			if (drain != null && drain.isDrained()) {
				SipServletResponse draining = request.createResponse(503, "Draining");
				int drainRetryAfter = settings.getDrainRetryAfter();
				if (drainRetryAfter > 0) {
					draining.setHeader("Retry-After", Integer.toString(drainRetryAfter));
				}
				sendResponse(draining);
				return;
			}

			// Drain signal: while OCCAS overload protection is rejecting traffic,
			// answer the health check 503 so a SIP-aware load balancer stops
			// routing new calls here. Falls through to the normal 200 OK whenever
			// the feature is off or the engine is not overloaded.
			if (settings.isUnavailableWhenOverloaded() && EngineOverload.isOverloaded()) {
				SipServletResponse busy = request.createResponse(503);
				int retryAfter = settings.getOverloadRetryAfter();
				if (retryAfter > 0) {
					busy.setHeader("Retry-After", Integer.toString(retryAfter));
				}
				sendResponse(busy);
				return;
			}

			SipServletResponse response = request.createResponse(200);
			response.setHeader("Accept", settings.getAccept());
			response.setHeader("Accept-Language", settings.getAcceptLanguage());
			response.setHeader("Allow", settings.getAllow());
			response.setHeader("User-Agent", settings.getUserAgent());
			response.setHeader("Allow-Events", settings.getAllowEvents());

			sendResponse(response);

		} catch (Exception ex) {
			sipLogger.severe(ex);
		}

	}

}
