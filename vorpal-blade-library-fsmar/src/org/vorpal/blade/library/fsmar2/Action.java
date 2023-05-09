/**
 *  MIT License
 *  
 *  Copyright (c) 2013, 2022 Vorpal Networks, LLC
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

package org.vorpal.blade.library.fsmar2;

import java.io.Serializable;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;
import javax.servlet.sip.ar.SipRouteModifier;

import org.vorpal.blade.framework.config.SettingsManager;

public class Action implements Serializable {
	private static final long serialVersionUID = 1L;
	public String terminating;
	public String originating;

	public String[] route;
	public String[] route_back;
	public String[] route_final;

	public SipApplicationRouterInfo createRouterInfo(String next, AppRouterConfiguration config, SipServletRequest request) {
		String subscriberURI = null;
		SipApplicationRoutingRegion region;
		region = SipApplicationRoutingRegion.NEUTRAL_REGION;

		if (originating != null) {
			region = SipApplicationRoutingRegion.ORIGINATING_REGION;
			try {
				subscriberURI = request.getAddressHeader(originating).getURI().toString();
			} catch (Exception e) {
				region = SipApplicationRoutingRegion.NEUTRAL_REGION;
				subscriberURI = null;
				SettingsManager.getSipLogger()
						.severe("Invalid address header: " + originating + ", setting routing region to neutral.");
				SettingsManager.getSipLogger().logStackTrace(e);
			}
		} else if (terminating != null) {
			region = SipApplicationRoutingRegion.TERMINATING_REGION;
			try {
				subscriberURI = request.getAddressHeader(terminating).getURI().toString();
			} catch (Exception e) {
				region = SipApplicationRoutingRegion.NEUTRAL_REGION;
				subscriberURI = null;
				SettingsManager.getSipLogger()
						.severe("Invalid address header: " + terminating + ", setting routing region to neutral.");
				SettingsManager.getSipLogger().logStackTrace(e);
			}
		} else {
			region = SipApplicationRoutingRegion.NEUTRAL_REGION;
		}

		SipRouteModifier mod = SipRouteModifier.NO_ROUTE;
		String[] routes = null;
		if (route != null) {
			routes = route;
			mod = SipRouteModifier.ROUTE;
		} else if (route_back != null) {
			routes = route_back;
			mod = SipRouteModifier.ROUTE_BACK;
		} else if (route_final != null) {
			routes = route_final;
			mod = SipRouteModifier.ROUTE_FINAL;
		}

		return new SipApplicationRouterInfo(next, region, subscriberURI, routes, mod, config);
	}

}
