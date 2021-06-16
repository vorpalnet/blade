package org.vorpal.blade.services.options;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.config.Settings;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class OptionsSipServlet extends SipServlet implements SipServletListener {
	private static final long serialVersionUID = 1L;
	private static Logger logger;

	private static OptionsSettings settings = null;

	@Override
	protected void doOptions(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(200);
		response.setHeader("Accept", settings.getAccept());
		response.setHeader("Accept-Language", settings.getAcceptLanguage());
		response.setHeader("Allow", settings.getAllow());
		response.setHeader("User-Agent", settings.getUserAgent());
		response.setHeader("Allow-Events", settings.getAllowEvents());
		response.send();
		
		logger.finest(request.getRawContent().toString());
		logger.finest(response.getRawContent().toString());
	}

	@Override
	public void servletInitialized(SipServletContextEvent event) {
		logger = LogManager.getLogger(event.getServletContext());

		Settings config = new Settings(event);
		try {
			settings = (OptionsSettings) config.load(OptionsSettings.class);
		} catch (Exception ex) {
			// ex.printStackTrace();
		}

		if (settings == null) {
			settings = new OptionsSettings();
			try {
				config.save(settings);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		logger.logConfiguration(settings);

	}

}
