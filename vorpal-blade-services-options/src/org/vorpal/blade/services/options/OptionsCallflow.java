package org.vorpal.blade.services.options;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.callflow.Callflow;

public class OptionsCallflow extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		OptionsSettings settings = OptionsSipServlet.settingsManager.getCurrent();

		SipServletResponse response = request.createResponse(200);
		response.setHeader("Accept", settings.getAccept());
		response.setHeader("Accept-Language", settings.getAcceptLanguage());
		response.setHeader("Allow", settings.getAllow());
		response.setHeader("User-Agent", settings.getUserAgent());
		response.setHeader("Allow-Events", settings.getAllowEvents());

		sendResponse(response);
	}

}
