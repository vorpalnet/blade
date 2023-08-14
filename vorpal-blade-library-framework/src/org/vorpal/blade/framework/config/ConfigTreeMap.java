package org.vorpal.blade.framework.config;

import java.util.TreeMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigTreeMap extends TranslationsMap {
	public TreeMap<String, Translation> map = new TreeMap<>();

	public int size() {
		return map.size();
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {

			// jwm - multiple selectors
			for (Selector selector : this.selectors) {

				RegExRoute regexRoute = selector.findKey(request);
				if (regexRoute != null) {
					value = map.get(regexRoute.key);
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