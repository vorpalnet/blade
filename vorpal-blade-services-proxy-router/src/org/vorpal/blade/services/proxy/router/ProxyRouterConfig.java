package org.vorpal.blade.services.proxy.router;

import java.io.Serializable;
import java.util.LinkedList;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.config.Translation;
import org.vorpal.blade.framework.config.TranslationsMap;

public class ProxyRouterConfig implements Serializable {
	public LinkedList<Selector> selectors = new LinkedList<>();
	public LinkedList<TranslationsMap> maps = new LinkedList<>();
	public LinkedList<TranslationsMap> plan = new LinkedList<>();
	public Translation defaultRoute = new Translation();

	public URI findRoute(SipServletRequest request) throws ServletParseException {
		URI uri = null;
		Translation t = null;

		for (TranslationsMap map : plan) {

			t = map.applyTranslations(request);

			if (t != null) {
				uri = SettingsManager.sipFactory.createURI(t.getRequestUri());
				break;
			}
		}

		if (uri == null && this.defaultRoute.requestUri != null) {
			uri = SettingsManager.getSipFactory().createURI(defaultRoute.requestUri);

			// copy all SIP URI parameters (if not already present in new request URI)
			for (String name : request.getRequestURI().getParameterNameSet()) {
				if (uri.getParameter(name) == null) {
					uri.setParameter(name, uri.getParameter(name));
				}
			}

		}

		return uri;
	}

}
