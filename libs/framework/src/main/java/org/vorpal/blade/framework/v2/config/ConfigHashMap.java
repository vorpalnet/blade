package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Translation map using HashMap for key-based lookups.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")
public class ConfigHashMap extends TranslationsMap implements Serializable {
	private static final long serialVersionUID = 1L;

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

				if (regexRoute != null) {
					value = map.get(regexRoute.key);
				}

				if (value != null) {
					value = new Translation(value);
					if (value.getAttributes() == null) {
						value.setAttributes(new HashMap<>());
					}
					if (regexRoute.attributes != null) {
						value.getAttributes().putAll(regexRoute.attributes);
					}
					break;
				}
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
