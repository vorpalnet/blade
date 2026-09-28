package org.vorpal.blade.services.hold;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v2.b2bua.Terminate;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.AsyncSipServlet;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;
import org.vorpal.blade.framework.v3.media.CallflowHold;

/// Parks a call: answers the dialog itself with inactive media and holds the
/// dialog open until the far end resumes or hangs up. A single-dialog UAS — there
/// is no second party and no transfer.
///
/// @author Jeff McDonald
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class HoldServlet extends AsyncSipServlet {

	private static final long serialVersionUID = 1L;
	public static SettingsManager<HoldSettings> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, HoldSettings.class, new HoldSettingsSample());
		sipLogger.info("servletCreated...");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			sipLogger.info("servletDestroyed...");
			settingsManager.unregister();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/// When the call was parked, for `heldMs`.
	private static final String HELD_AT = "blade.hold.heldAt";

	/// Publish [BladeEventTypes#CALL_HOLD_ENDED] once, on the BYE or CANCEL that
	/// ends the park.
	private static void holdEnded(SipServletRequest request) {
		javax.servlet.sip.SipApplicationSession app = request.getApplicationSession();
		Object heldAt = app.getAttribute(HELD_AT);
		if (heldAt instanceof Long) {
			app.removeAttribute(HELD_AT);
			long heldMs = System.currentTimeMillis() - (Long) heldAt;
			Events.publish(app, BladeEventTypes.CALL_HOLD_ENDED, data -> data.put("heldMs", heldMs));
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		switch (request.getMethod()) {
		case "INVITE":
			callflow = new CallflowHold();
			if (request.isInitial()) {
				request.getApplicationSession().setAttribute(HELD_AT, Long.valueOf(System.currentTimeMillis()));
				Events.publish(request.getApplicationSession(), BladeEventTypes.CALL_HELD, null);
			}
			break;

		case "CANCEL":
		case "BYE":
			callflow = new Terminate(null);
			holdEnded(request);
			break;

		case "ACK":
			break;

		default:
			callflow = new HoldMethodNotAllowed();
		}

		return callflow;
	}

}
