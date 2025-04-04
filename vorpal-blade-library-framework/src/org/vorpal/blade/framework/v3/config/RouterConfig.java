package org.vorpal.blade.framework.v3.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

public class RouterConfig<T> extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	public Map<String, AttributeSelector> selectors = new HashMap<>();
	public Map<String, TranslationsMap> maps = new HashMap<>();
	public List<String> plan = new LinkedList<>();

	private Map<String, TranslationsMap<T>> planMap = new HashMap<>();

	public Translation<T> defaultRoute = null;

	public List<String> getPlan() {
		return plan;
	}

	public void setPlan(List<String> plan) {
		this.plan = plan;

		for (String p : plan) {
			planMap.put(p, maps.get(p));
		}
	}

//	public  URI applyParameters(Translation<T> t, SipServletRequest request) throws ServletParseException {
//		URI uri = null;
//
//		if (t != null) {
//			
//			uri = SettingsManager.sipFactory.createURI(t.getRequestUri());
//
//			// copy the 'user' if necessary
//			SipURI tSipUri = (SipURI) uri;
//			SipURI rSipUri = (SipURI) request.getRequestURI();
//			if (tSipUri.getUser() == null && rSipUri.getUser() != null) {
//				tSipUri.setUser(rSipUri.getUser());
//			}
//
//			// copy all SIP URI parameters (if not already present in new request URI)
//			for (String name : request.getRequestURI().getParameterNameSet()) {
//				if (!name.equals("tag")) {
//					if (uri.getParameter(name) == null) {
//						uri.setParameter(name, uri.getParameter(name));
//					}
//				}
//			}
//
//		}
//
//		return uri;
//	}

	public Translation<T> findTranslation(SipServletRequest request) throws ServletParseException {
		Logger sipLogger = SettingsManager.getSipLogger();
		Translation<T> t = null;

		sipLogger.finer(request, "Translation.findTranslation() searching maps size: " + plan.size());

		for (TranslationsMap<T> map : planMap.values()) {
//			sipLogger.finer(request, "Translation.findTranslation() searching map: " + map.getId());
			t = map.applyTranslations(request);
			if (t != null) {
				break;
			}
		}

		if (t != null) {
			sipLogger.finer(request, "Found translation id: " + t.getId() + //
					", desc: " + t.getDescription() + //
					", route-group: " + t.getAttribute("route-group"));
		} else {
			sipLogger.finer(request, "No match found, using default.");
		}

		return (null != t) ? t : defaultRoute;
	}

//	public URI findRoute(SipServletRequest request) throws ServletParseException {
//
//		Translation<T> t = null;
//		URI uri = null;
//
//		t = findTranslation(request);
////		uri = applyParameters(t, request);
//
//		return uri;
//	}

}
