package org.vorpal.blade.framework.config;

import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

//@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="@type")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")

public class ConfigHashMap extends TranslationsMap {

	public HashMap<String, Translation> map = new HashMap<>();

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
