package org.vorpal.blade.framework.v3.config;

import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMap<T> extends TranslationsMap<T> {

	Logger sipLogger = SettingsManager.getSipLogger();

	public HashMap<String, Translation<T>> map = new HashMap<>();

	public int size() {
		return map.size();
	}

	public Translation<T> createTranslation(String key) {
		Translation<T> t = new Translation<>();
		map.put(key, t);
		return t;
	}

	@Override
	public Translation<T> lookup(SipServletRequest request) {
		Translation<T> value = null;

		try {
			AttributesKey regexRoute = null;

			for (AttributeSelector selector : this.selectors) {

				regexRoute = selector.findKey(request);

				if (regexRoute != null) {

					String substring;
					for (int i = regexRoute.key.length(); i > 0; --i) {
						substring = regexRoute.key.substring(0, i);

						sipLogger.finer(request, "prefix=" + substring);

						value = map.get(substring);
						if (value != null) {
							break;
						}

					}

					if (value != null) {
						value = new Translation<>(value);
						if (value.getAttributes() == null) {
							value.setAttributes(new HashMap<>());
						}
						if (regexRoute.attributes != null) {
							value.getAttributes().putAll(regexRoute.attributes);
						}

						break;
					}

				}

			}

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

		return value;
	}

}
