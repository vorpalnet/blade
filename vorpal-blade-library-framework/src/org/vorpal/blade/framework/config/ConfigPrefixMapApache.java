package org.vorpal.blade.framework.config;

import java.util.HashMap;
import java.util.SortedMap;

import javax.servlet.sip.SipServletRequest;

import org.apache.commons.collections4.trie.PatriciaTrie;
import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMapApache
extends TranslationsMap {

	Logger sipLogger = SettingsManager.getSipLogger();

	public PatriciaTrie<Translation> map = new PatriciaTrie<>();

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
					SortedMap<String, Translation> sortedMap;
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
			RegExRoute regexRoute = null;

			for (Selector selector : this.selectors) {

				regexRoute = selector.findKey(request);

				if (regexRoute != null) {

					String substring;
					SortedMap<String, Translation> sortedMap;
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
