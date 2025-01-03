package org.vorpal.blade.framework.v2.config;

import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;

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

		Logger sipLogger = Callflow.getSipLogger();

		try {
			for (Selector selector : this.selectors) {

				sipLogger.finest("selector.id=" + selector.getId());

				RegExRoute regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					sipLogger.finest("regexRoute header=" + regexRoute.header + ", key=" + regexRoute.key);
					value = map.get(regexRoute.key);
					sipLogger.finest("value=" + value);
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
