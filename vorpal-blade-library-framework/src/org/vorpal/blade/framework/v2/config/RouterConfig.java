package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;

public class RouterConfig extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	public LinkedList<Selector> selectors = new LinkedList<>();
	public LinkedList<TranslationsMap> maps = new LinkedList<>();
	public LinkedList<TranslationsMap> plan = new LinkedList<>();

	// jwm - don't create a blank default route? simpler, i think.
	// public Translation defaultRoute = new Translation("defaultRoute");
	public Translation defaultRoute = null;

	public RouterConfig() {

	}

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

	public static URI applyParameters(Translation t, SipServletRequest request) throws ServletParseException {
		URI to = null;
		URI from;

		if (t != null && t.getRequestUri() != null) {
			to = SettingsManager.sipFactory.createURI(t.getRequestUri());
			from = request.getRequestURI();

			Callflow.copyParameters(from, to);
		}

		return to;
	}

	public Translation findTranslation(SipServletRequest request) throws ServletParseException {
		Logger sipLogger = SettingsManager.getSipLogger();
		Translation t = null;

//		sipLogger.finer(request, "Translation.findTranslation() searching maps size: " + plan.size());

		for (TranslationsMap map : plan) {
//			sipLogger.finer(request, "Translation.findTranslation() searching map: " + map.getId());
			t = map.applyTranslations(request);
			if (t != null) {
				break;
			}
		}

		if (t != null) {

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(request, "Found translation id=" + t.getId() + //
						", desc=" + t.getDescription() + //
						", ruri=" + t.getRequestUri() + //
						", attributes=" + t.getAttributes());
			}

		} else {
			t = defaultRoute;
			if (t != null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request,
							"No translation found, using defaultRoute in config file. id=" + t.getId() + //
									", desc=" + t.getDescription() + //
									", ruri=" + t.getRequestUri() + //
									", attributes=" + t.getAttributes());
				}
			} else {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request, "No translation found, returning null.");
				}
			}
		}

		return t;
	}

	public URI findRoute(SipServletRequest request) throws ServletParseException {

		Translation t = null;
		URI uri = null;

		t = findTranslation(request);
		if (t != null & t.getRequestUri() != null) {
			uri = applyParameters(t, request);
		} else {
			uri = request.getRequestURI();
		}

		return uri;
	}

}
