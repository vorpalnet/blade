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

			// Boot gate: OCCAS accepts SIP traffic while deployments are still
			// in progress, and the App Router would route early calls through a
			// partial chain (missing apps bypass as virtual states). Until this
			// server reaches RUNNING — the end of the deploy phase, ALL apps
			// processed — keep the load balancer away. See ServerReady.
			if (settings.isUnavailableUntilRunning() && !ServerReady.isReady()) {
				sendResponse(request.createResponse(503, "Starting"));
				return;
			}

			// Administrative drain: the operator took this node out of rotation
			// via the Drain MBean (runtime state, not config — see DrainControl).
			// Checked BEFORE queue pressure: explicit intent outranks
			// automatic protection. The "Draining" reason phrase distinguishes
			// this 503 from the "Busy" one in a trace; load balancers treat
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

			// Queue pressure: a SIP work-manager queue is past the configured
			// share of its capacity. At 100% the container answers 503 itself,
			// to calls as well as pings; this 503 comes first. No Retry-After:
			// the node rejoins as soon as a ping finds the queues below the line.
			Integer pressure = settings.getQueuePressurePercent();
			if (QueuePressure.isPressured(pressure != null ? pressure : QueuePressure.DEFAULT_PERCENT)) {
				sendResponse(request.createResponse(503, "Busy"));
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
