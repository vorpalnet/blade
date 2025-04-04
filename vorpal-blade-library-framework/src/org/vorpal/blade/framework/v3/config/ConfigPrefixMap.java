package org.vorpal.blade.framework.v3.config;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMap<T> extends HashMap<String, Translation<T>> implements TranslationsMap<T> {
	private static final long serialVersionUID = 1L;

	static Logger sipLogger = SettingsManager.getSipLogger();

//	private List<String> selectors = new LinkedList<>();
	private Map<String, AttributeSelector> selectorMap = new HashMap<>();
	
	@Override
	public void addSelector(String id, AttributeSelector sel) {
		selectors.add(id);
		this.selectorMap.put(id, sel);
	}
	


	public Translation<T> createTranslation(String key) {
		Translation<T> t = new Translation<>();
		this.put(key, t);
		return t;
	}

	@Override
	public Translation<T> lookup(SipServletRequest request) {
		Translation<T> value = null;

		if (sipLogger == null)
			sipLogger = SettingsManager.getSipLogger();

		try {
			AttributesKey regexRoute = null;

			for (AttributeSelector selector : selectorMap.values()) {

				regexRoute = selector.findKey(request);

				if (regexRoute != null) {

					String substring;
					for (int i = regexRoute.key.length(); i > 0; --i) {
						substring = regexRoute.key.substring(0, i);

						sipLogger.finer(request, "prefix=" + substring);

						value = this.get(substring);
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
