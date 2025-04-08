package org.vorpal.blade.framework.v3.config;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

public class RouterConfig<T> extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	public List<AttributeSelector> selectors = new LinkedList<>();
	public List<TranslationsMap<T>> maps = new LinkedList<>();
	public List<TranslationsMap<T>> plans = new LinkedList<>();
	public Translation<T> defaultRoute = null;

	public Translation<T> findTranslation(SipServletRequest request) throws ServletParseException {
		Logger sipLogger = SettingsManager.getSipLogger();
		Translation<T> t = null;

		sipLogger.finer(request, "Translation.findTranslation() searching maps size: " + plans.size());

		for (TranslationsMap<T> map : plans) {
			t = map.applyTranslations(request);
			if (t != null) {
				break;
			}
		}

		if (t != null) {
			sipLogger.finer(request, "Found translation id: " + t.getId() + ", desc: " + t.getDesc());
		} else {
			sipLogger.finer(request, "No match found, using default.");
		}

		return (null != t) ? t : defaultRoute;
	}

	public List<AttributeSelector> getSelectors() {
		return selectors;
	}

	public RouterConfig<T> setSelectors(List<AttributeSelector> selectors) {
		this.selectors = selectors;
		return this;
	}

	public List<TranslationsMap<T>> getMaps() {
		return maps;
	}

	public RouterConfig<T> setMaps(List<TranslationsMap<T>> maps) {
		this.maps = maps;
		return this;
	}

	public List<TranslationsMap<T>> getPlans() {
		return plans;
	}

	public RouterConfig<T> setPlans(List<TranslationsMap<T>> plans) {
		this.plans = plans;
		return this;
	}

	public Translation<T> getDefaultRoute() {
		return defaultRoute;
	}

	public RouterConfig<T> setDefaultRoute(Translation<T> defaultRoute) {
		this.defaultRoute = defaultRoute;
		return this;
	}

}
