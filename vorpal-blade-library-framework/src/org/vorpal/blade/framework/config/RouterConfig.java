package org.vorpal.blade.framework.config;

import java.io.Serializable;
import java.util.LinkedList;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.logging.Logger;

public class RouterConfig implements Serializable {
	private static final long serialVersionUID = 1L;
	public LinkedList<Selector> selectors = new LinkedList<>();
	public LinkedList<TranslationsMap> maps = new LinkedList<>();
	public LinkedList<TranslationsMap> plan = new LinkedList<>();
	public Translation defaultRoute = new Translation();

	public static URI applyParameters(Translation t, SipServletRequest request) throws ServletParseException {
		URI uri = null;

		if (t != null) {
			uri = SettingsManager.sipFactory.createURI(t.getRequestUri());

			// copy all SIP URI parameters (if not already present in new request URI)
			for (String name : request.getRequestURI().getParameterNameSet()) {
				if (uri.getParameter(name) == null) {
					uri.setParameter(name, uri.getParameter(name));
				}
			}

		}
		return uri;
	}

	public Translation findTranslation(SipServletRequest request) throws ServletParseException {
		Logger sipLogger = SettingsManager.getSipLogger();
		Translation t = null;

		for (TranslationsMap map : plan) {
			t = map.applyTranslations(request);
			if (t != null) {
				break;
			}
		}

		if (t != null) {
			sipLogger.finer(request, "Found Translation (id): " + t.getId());
		} else {
			sipLogger.finer(request, "No match found, using Translation (id): " + defaultRoute.getId());
		}

		return (null != t) ? t : defaultRoute;
	}

	public URI findRoute(SipServletRequest request) throws ServletParseException {

		Translation t = null;
		URI uri = null;

		t = findTranslation(request);
		uri = applyParameters(t, request);

		return uri;
	}

}
