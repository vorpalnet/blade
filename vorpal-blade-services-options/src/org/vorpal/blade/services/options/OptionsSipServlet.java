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
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class OptionsSipServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<OptionsSettings> settingsManager;

	/**
	 * This is an attempt at optimization. Instead of creating a new
	 * SipApplicationSession for ever OPTIONS ping, reuse an existing one. We'll use
	 * the remote IP address as the session key so as to not unnecessarily
	 * single-thread things.
	 * 
	 * @param request
	 * @return the UAC's IP address
	 */
//	@SipApplicationKey
//	public static String sessionKey(SipServletRequest request) {
//		return request.getRemoteAddr();
//	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {

		try {

			settingsManager = new SettingsManager<>(event, OptionsSettings.class, new OptionsSettingsSample());

		} catch (Exception ex) {
			if (sipLogger != null) {
				sipLogger.severe("Unable to load options.json configuration file.");
				sipLogger.severe(ex);
			} else {
				System.out.println("Unable to load options.json configuration file.");
				ex.printStackTrace();

			}
		}

	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		// do nothing;
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		return new OptionsCallflow();
	}

}
