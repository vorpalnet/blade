package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;

/**
 * Router configuration with selectors, translation maps and a default route.
 */
public class RouterConfig extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	public LinkedList<Selector> selectors = new LinkedList<>();
	public LinkedList<TranslationsMap> maps = new LinkedList<>();
	public LinkedList<TranslationsMap> plan = new LinkedList<>();

	public Translation defaultRoute = null;

	public RouterConfig() {

	}

	public static URI applyParameters(Translation t, SipServletRequest request) throws ServletParseException {
		URI to = null;
		URI from;

		if (t != null && t.getRequestUri() != null) {

			String ruri = t.getRequestUri();

			HashMap<String, String> attrMap = new HashMap<>();
			Object objValue;
			for (String name : request.getApplicationSession().getAttributeNameSet()) {
				objValue = request.getApplicationSession().getAttribute(name);
				if (objValue instanceof String) {
					Callflow.getSipLogger().finest(request,
							"RouterConfig.applyParameters - TranslationsMap setting attrMap name=" + name + ", value="
									+ objValue);
					attrMap.put(name, (String) objValue);
				}
			}
			ruri = Configuration.resolveVariables(attrMap, ruri);

			to = SettingsManager.sipFactory.createURI(ruri);
			from = request.getRequestURI();

			Callflow.copyParameters(from, to);
		}

		return to;
	}

	public Translation findTranslation(SipServletRequest request) throws ServletParseException {
		Translation t = null;

		Callflow.getSipLogger().finer(request, "RouterConfig.findTranslation - begin...");

		Callflow.getSipLogger().finer(request, "RouterConfig.findTranslation - searching maps size: " + plan.size());

		for (TranslationsMap map : plan) {
			Callflow.getSipLogger().finer(request, "RouterConfig.findTranslation -  searching map: " + map.getId());
			t = map.applyTranslations(request);
			if (t != null) {
				break;
			}
		}

		if (t != null) {

			if (Callflow.getSipLogger().isLoggable(Level.FINER)) {
				Callflow.getSipLogger().finer(request, "RouterConfig.findTranslation - Found translation id=" + t.getId() + //
						", desc=" + t.getDescription() + //
						", ruri=" + t.getRequestUri() + //
						", attributes=" + t.getAttributes());
			}

		} else {
			t = defaultRoute;
			if (t != null) {
				if (Callflow.getSipLogger().isLoggable(Level.FINER)) {
					Callflow.getSipLogger().finer(request,
							"RouterConfig.findTranslation - No translation found, using defaultRoute in config file. id="
									+ t.getId() + //
									", desc=" + t.getDescription() + //
									", ruri=" + t.getRequestUri() + //
									", attributes=" + t.getAttributes());
				}
			} else {
				if (Callflow.getSipLogger().isLoggable(Level.FINER)) {
					Callflow.getSipLogger().finer(request, "RouterConfig.findTranslation - No translation found, returning null.");
				}
			}
		}

		Callflow.getSipLogger().finer(request, "RouterConfig.findTranslation - end. t="+t);
		return t;
	}

	public URI findRoute(SipServletRequest request) throws ServletParseException {

		Translation t = null;
		URI uri = null;
		Callflow.getSipLogger().finer(request, "RouterConfig.findRoute - begin...");
		t = findTranslation(request);
		Callflow.getSipLogger().finer(request, "RouterConfig.findRoute - t=" + t);
		if (t != null && t.getRequestUri() != null) {
			Callflow.getSipLogger().finer(request, "RouterConfig.findRoute - applying parameters...");
			uri = applyParameters(t, request);
			Callflow.getSipLogger().finer(request, "RouterConfig.findRoute - parameters applied. uri=" + uri);
		} else {
			Callflow.getSipLogger().finer(request, "RouterConfig.findRoute - no translation found.");
			uri = request.getRequestURI();
			Callflow.getSipLogger().finer(request, "RouterConfig.findRoute - using requestURI, uri=" + uri);
		}

		Callflow.getSipLogger().finer(request, "RouterConfig.findRoute - end. uri=" + uri);
		return uri;
	}

}
