/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.services.options;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class OptionsSipServlet extends SipServlet implements SipServletListener {
	private static final long serialVersionUID = 1L;
	private static Logger logger;
	private static SettingsManager<OptionsSettings> settingsManager;

	@Override
	public void servletInitialized(SipServletContextEvent event) {
		logger = LogManager.getLogger(event.getServletContext());
		settingsManager = new SettingsManager<>(event.getServletContext().getServletContextName(), OptionsSettings.class);
	}

	@Override
	protected void doOptions(SipServletRequest request) throws ServletException, IOException {

		OptionsSettings settings = settingsManager.getCurrent();

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

}
