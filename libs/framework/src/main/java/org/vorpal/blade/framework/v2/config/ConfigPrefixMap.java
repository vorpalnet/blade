package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/**
 * Translation map with prefix-based lookup using iterative substring matching.
 */
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMap extends TranslationsMap implements Serializable {
	private static final long serialVersionUID = 1L;

	public HashMap<String, Translation> map = new HashMap<>();

	public int size() {
		return map.size();
	}

	public Translation createTranslation(String key) {
		Translation t = new Translation();
		map.put(key, t);
		return t;
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {
			RegExRoute regexRoute = null;

			for (Selector selector : this.selectors) {

				regexRoute = selector.findKey(request);

				if (regexRoute != null) {

					String substring;
					for (int i = regexRoute.key.length(); i > 0; --i) {
						substring = regexRoute.key.substring(0, i);

						value = map.get(substring);
						if (value != null) {
							break;
						}

					}

					if (value != null) {
						value = new Translation(value);
						if (regexRoute.attributes != null && !regexRoute.attributes.isEmpty()) {
							value.getAttributes().putAll(regexRoute.attributes);
						}
						break;
					}

				}

			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(e);
		}

		return value;
	}

}
