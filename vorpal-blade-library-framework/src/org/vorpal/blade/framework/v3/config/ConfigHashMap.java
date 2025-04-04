package org.vorpal.blade.framework.v3.config;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

//@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="@type")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")

public class ConfigHashMap<T> extends HashMap<String, Translation<T>> implements TranslationsMap<T> {
	private static final long serialVersionUID = 1L;
//	public List<String> selectors = new LinkedList<>();
	private Map<String, AttributeSelector> selectorMap = new HashMap<>();
	static Logger sipLogger = SettingsManager.getSipLogger();


	@Override
	public void addSelector(String id, AttributeSelector sel) {
		selectors.add(id);
		this.selectorMap.put(id, sel);
	}

	@Override
	public Translation<T> lookup(SipServletRequest request) {
		Translation<T> value = null;

		if (sipLogger == null)
			sipLogger = SettingsManager.getSipLogger();
		
		try {
			for (AttributeSelector selector : this.selectorMap.values()) {

				sipLogger.finer("selector.id=" + selector.getId());

				AttributesKey regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					value = this.get(regexRoute.key);
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
		this.put(key, value);
		return value;
	}

}
