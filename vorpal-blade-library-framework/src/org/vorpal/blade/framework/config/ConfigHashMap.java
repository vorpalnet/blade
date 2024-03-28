package org.vorpal.blade.framework.config;

import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

//@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="@type")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")

public class ConfigHashMap extends TranslationsMap {

	public HashMap<String, Translation> map = new HashMap<>();

	public int size() {
		return map.size();
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {
			for (Selector selector : this.selectors) {

				RegExRoute regexRoute = selector.findKey(request);

				if (regexRoute != null && regexRoute.key != null) {
					value = new Translation(map.get(regexRoute.key));
					if (value.getAttributes() == null) {
						value.setAttributes(new HashMap<>());
					}
					if (value != null && regexRoute.attributes != null) {
						value.getAttributes().putAll(regexRoute.attributes);
					}
				}

				if (value != null)
					break;

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
