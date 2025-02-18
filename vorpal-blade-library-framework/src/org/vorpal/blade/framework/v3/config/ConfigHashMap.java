package org.vorpal.blade.framework.v3.config;

import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

//@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="@type")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")

public class ConfigHashMap<T> extends TranslationsMap<T> {

	public HashMap<String, Translation<T>> map = new HashMap<>();

	public int size() {
		return map.size();
	}

	@Override
	public Translation<T> lookup(SipServletRequest request) {
		Translation<T> value = null;

		Logger sipLogger = Callflow.getSipLogger();

		try {
			for (AttributeSelector selector : this.selectors) {

				sipLogger.finer("selector.id=" + selector.getId());

				AttributesKey regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					value = map.get(regexRoute.key);
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
