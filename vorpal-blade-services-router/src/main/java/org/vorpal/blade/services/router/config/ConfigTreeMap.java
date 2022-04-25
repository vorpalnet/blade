package org.vorpal.blade.services.router.config;

import java.util.TreeMap;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public  class ConfigTreeMap extends TranslationsMap {
	public TreeMap<String, Translation> map = new TreeMap<>();

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {

			RegExRoute regexRoute = this.selector.findKey(request);
			if (regexRoute != null) {
				value = map.get(regexRoute.key);
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(e);
		}

		return value;
	}

	@Override
	public Translation createTranslation(String key) {
		Translation value = new Translation();
		map.put(key, value);
		return value;
	}
}