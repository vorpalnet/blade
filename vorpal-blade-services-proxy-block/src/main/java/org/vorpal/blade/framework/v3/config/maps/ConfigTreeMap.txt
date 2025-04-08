package org.vorpal.blade.framework.v3.config;

import java.util.HashMap;
import java.util.TreeMap;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigTreeMap<T> extends TranslationsMap<T> {
	public TreeMap<String, Translation<T>> map = new TreeMap<>();

	public int size() {
		return map.size();
	}

	@Override
	public Translation<T> lookup(SipServletRequest request) {
		Translation<T> value = null;

		try {
			for (AttributeSelector selector : this.selectors) {
				AttributesKey regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					value = map.get(regexRoute.key);
				}

				if (value != null) {
					value = new Translation<T>(value);
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
	public Translation<T> createTranslation(String key) {
		Translation<T> value = new Translation<>();
		map.put(key, value);
		return value;
	}
}