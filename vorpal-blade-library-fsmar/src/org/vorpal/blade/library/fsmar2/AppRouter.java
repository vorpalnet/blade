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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;

import javax.servlet.ServletContext;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.ar.SipApplicationRouter;
import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;
import javax.servlet.sip.ar.SipRouteModifier;
import javax.servlet.sip.ar.SipTargetedRequestInfo;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

import com.bea.wcp.sip.engine.SipServletRequestAdapter;
import com.bea.wcp.sip.engine.server.SipApplicationSessionImpl;

public class AppRouter implements SipApplicationRouter {
	protected static String FSMAR = "fsmar2";
	public static Logger sipLogger;
	private static SettingsManager<Configuration> settingsManager;
	protected static HashMap<String, String> deployed = new HashMap<>();

	@Override
	public void init() {
		settingsManager = new SettingsManager<Configuration>(FSMAR, Configuration.class);
		
		System.out.println("Settings: "+settingsManager.getCurrent());
		System.out.println("Settings: "+settingsManager.getCurrentAsJson());
		
		
		
		sipLogger = settingsManager.getSipLogger();
		sipLogger.logConfiguration(settingsManager.getCurrent());
	}

	@Override
	public void init(Properties properties) {
		this.init();
	}

	@Override
	public SipApplicationRouterInfo getNextApplication(SipServletRequest request, SipApplicationRoutingRegion region,
			SipApplicationRoutingDirective directive, SipTargetedRequestInfo requestInfo, Serializable saved) {
		SipApplicationRouterInfo nextApp = null;

		try {

			ServletContext context = request.getServletContext();
			Configuration config = (saved != null) ? (Configuration) saved : settingsManager.getCurrent();

			// Did the request originate from an app?
			SipApplicationSessionImpl sasi = ((SipServletRequestAdapter) request).getImpl()
					.getSipApplicationSessionImpl();
			String previous = (sasi != null) ? SettingsManager.basename(sasi.getApplicationName()) : "null";

			if (sipLogger.isLoggable(Level.FINE)) {
				String str = "getNextApplication... " + request.getMethod() + " " + request.getRequestURI();
				str += ", previous: " + previous;
				str += (region != null) ? ", region: " + region.getLabel() + " " + region.getType() : "";
				str += (directive != null) ? ", directive: " + directive : "";
				str += (requestInfo != null)
						? ", requestInfo" + requestInfo.getType() + " " + requestInfo.getApplicationName()
						: "";
				sipLogger.fine(str);
			}

			// For targeted sessions, skip all this nonsense and route to that app
			if (requestInfo != null) {
				String deployedApp = getDeployedApp(requestInfo.getApplicationName());
				nextApp = new SipApplicationRouterInfo(deployedApp, region, null, null, SipRouteModifier.NO_ROUTE,
						config);
			}

			// Is the request intended for a deployed app? i.e. "sip:hold"
			if (nextApp == null) {
//				SipURI sipUri = (SipURI) request.getTo().getURI(); // no, it should be the Request-URI
				SipURI sipUri = (SipURI) request.getRequestURI();
				
				if (null == sipUri.getUser()) {
					String app = getDeployedApp(sipUri.getHost());
					if (app != null) {
						nextApp = new SipApplicationRouterInfo(app, region, null, null, SipRouteModifier.NO_ROUTE,
								config);
					}
				}
			}

			// Iterate through all possible transitions
			if (nextApp == null) {
				State state = config.getPrevious(previous);

				Trigger trigger = state.getTrigger(request.getMethod()); // invite

				if (trigger != null) {

					boolean conditionMatches = false;
					Transition t = null;

					Iterator<Transition> itr = trigger.transitions.iterator();
					if (itr.hasNext()) {
						while (conditionMatches == false && itr.hasNext()) {
							t = itr.next();

							if (t.condition == null) {
								sipLogger.fine("null condition, implicit match!");
								conditionMatches = true;
							} else {
								conditionMatches = t.condition.checkAll(t.id, request);
								sipLogger.fine("Condition matches: " + conditionMatches);
							}

							if (conditionMatches) {
								sipLogger.fine("conditionMatches... break!");
								break;
							}

						} // while
					} else {
						sipLogger.fine("null transition, implicit match!");
						t = new Transition();
						conditionMatches = true;
					}

					if (conditionMatches) {

						if (t.action == null) {
							t.action = new Action();
						} else {
							sipLogger.fine("Action:" + "\n\toriginating=" + t.action.originating + "\n\tterminating="
									+ t.action.terminating + "\n\troute=" + t.action.route + "\n\troute_back="
									+ t.action.route_back + "\n\troute_final=" + t.action.route_final);
						}

						nextApp = t.action.createRouterInfo(deployed.get(t.next), config, request);
					}

				}

			}

			// Use the default application if necessary
			if (previous.equals("null") && (nextApp == null || nextApp.getNextApplicationName() == null
					|| nextApp.getNextApplicationName().equals("null"))) {
				sipLogger.warning("Using defaultApplication: " + config.getDefaultApplication() + //
						" for " + request.getMethod() + " " + request.getRequestURI());
				nextApp = new SipApplicationRouterInfo(deployed.get(config.getDefaultApplication()), region, null, null,
						SipRouteModifier.NO_ROUTE, config);
			}

			if (sipLogger.isLoggable(Level.FINE)) {
				if (nextApp != null) {
					String str = "RouterInfo... " + nextApp.getNextApplicationName();
					str += ", getSubscriberURI: " + nextApp.getSubscriberURI();
					if (nextApp.getRoutingRegion() != null) {
						str += ", getRoutingRegion().getLabel: " + nextApp.getRoutingRegion().getLabel();
					}
					if (nextApp.getRoutingRegion() != null) {
						str += ", getRoutingRegion().getType: " + nextApp.getRoutingRegion().getType();
					}
					str += ", getRouteModifier: " + nextApp.getRouteModifier();
					str += ", getRoutes: " + Arrays.toString(nextApp.getRoutes());
					sipLogger.fine(str);
				} else {
					sipLogger.fine("No match. Setting router info to null.");
				}
			}

		} catch (

		Exception e) {
			sipLogger.logStackTrace(e);
		}

		return nextApp;
	}

	@Override
	public void applicationDeployed(List<String> apps) {
		sipLogger.info("Application(s) deployed: " + Arrays.toString(apps.toArray()));
		for (String app : apps) {
			deployed.put(SettingsManager.basename(app), app);
		}
	}

	@Override
	public void applicationUndeployed(List<String> apps) {
		sipLogger.info("Application(s) undeployed: " + Arrays.toString(apps.toArray()));
		// Is it safe to remove from 'deployed'? Probably not...
	}

	@Override
	public void destroy() {
		LogManager.closeLogger(FSMAR);
	}

	public static String getDeployedApp(String appName) {
		String deployedApp = deployed.get(SettingsManager.basename(appName));
		if (deployedApp == null) {
			sipLogger.severe("Application " + appName + " is not deployed. Check configuration.");
		}

		return deployedApp;
	}

}
