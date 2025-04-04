package org.vorpal.blade.framework.v3.config;

import java.util.HashMap;
import java.util.SortedMap;

import javax.servlet.sip.SipServletRequest;

import org.apache.commons.collections4.trie.PatriciaTrie;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMapApache<T>
extends TranslationsMap<T> {

	Logger sipLogger = SettingsManager.getSipLogger();

	public PatriciaTrie<Translation<T>> map = new PatriciaTrie<>();

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
					SortedMap<String, Translation<T>> sortedMap;
					for (int i = regexRoute.key.length(); i > 0; --i) {
						substring = regexRoute.key.substring(0, i);

						sipLogger.finer(request, "prefix=" + substring);

						sortedMap = map.prefixMap(substring);
						if (false == sortedMap.isEmpty()) {
							value = sortedMap.get(substring);
							break;
						}

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

			}

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

		return value;
	}

//	@Override
	public Translation lookupWorks(SipServletRequest request) {
		Translation value = null;

		try {
			AttributesKey regexRoute = null;

			for (AttributeSelector selector : this.selectors) {

				regexRoute = selector.findKey(request);

				if (regexRoute != null) {

					String substring;
					SortedMap<String, Translation<T>> sortedMap;
					for (int i = regexRoute.key.length(); i > 0; --i) {
						substring = regexRoute.key.substring(0, i);

						sipLogger.finer(request, "prefix=" + substring);

						sortedMap = map.prefixMap(substring);

						if (sortedMap.containsKey(substring)) {
							value = sortedMap.get(substring);
							break;
						}
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

			}

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

		return value;
	}

}
