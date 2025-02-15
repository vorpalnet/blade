package org.vorpal.blade.services.options;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class OptionsCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		try {

			OptionsSettings settings = OptionsSipServlet.settingsManager.getCurrent();

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
