package org.vorpal.blade.framework.v3.config;

import java.io.Serializable;
import java.util.LinkedList;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.Configuration;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.Logger;

public class RouterConfig<T> extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	public LinkedList<Selector> selectors = new LinkedList<>();
	public LinkedList<TranslationsMap<T>> maps = new LinkedList<>();
	public LinkedList<TranslationsMap<T>> plan = new LinkedList<>();

	// jwm - don't create a blank default route? simpler, i think.
	// public Translation defaultRoute = new Translation("defaultRoute");
	public Translation<T> defaultRoute = null;

//	public static URI applyParameters(Translation t, SipServletRequest request) throws ServletParseException {
//		URI uri = null;
//
//		if (t != null) {
//			uri = SettingsManager.sipFactory.createURI(t.getRequestUri());
//
//			// copy all SIP URI parameters (if not already present in new request URI)
//			for (String name : request.getRequestURI().getParameterNameSet()) {
//				if (uri.getParameter(name) == null) {
//					uri.setParameter(name, uri.getParameter(name));
//				}
//			}
//
//		}
//		return uri;
//	}

	public Translation<T> findTranslation(SipServletRequest request) throws ServletParseException {
		Logger sipLogger = SettingsManager.getSipLogger();
		Translation<T> t = null;

		sipLogger.finer(request, "Translation.findTranslation() searching maps size: " + plan.size());

		for (TranslationsMap<T> map : plan) {
			sipLogger.finer(request, "Translation.findTranslation() searching map: " + map.getId());
			t = map.applyTranslations(request);
			if (t != null) {
				break;
			}
		}

		if (t != null) {
			sipLogger.finer(request, "Found translation id: " + t.getId() + //
					", desc: " + t.getDescription());
		} else {
			sipLogger.finer(request, "No match found, using default.");
		}

		return (null != t) ? t : defaultRoute;
	}

//	public URI findRoute(SipServletRequest request) throws ServletParseException {
//
//		Translation t = null;
//		URI uri = null;
//
//		t = findTranslation(request);
//		uri = applyParameters(t, request);
//
//		return uri;
//	}

}
