package org.vorpal.blade.framework.config;

import java.util.Iterator;
import java.util.Map.Entry;

import javax.servlet.sip.SipServletRequest;

import org.apache.commons.collections4.trie.PatriciaTrie;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMap extends TranslationsMap {

	public PatriciaTrie<Translation> map = new PatriciaTrie<>();

	public Translation createTranslation(String key) {
		Translation t = new Translation();
		map.put(key, t);
		return t;
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		Entry<String, Translation> entry = null;
//		Entry<String, Translation> previous = null;

		try {

			for (Selector selector : this.selectors) {
				Iterator<Entry<String, Translation>> itr = map.entrySet().iterator();

				SettingsManager.sipLogger.finest(request, "ConfigPrefixMap.lookup()... Calling findKey()");
				RegExRoute regexRoute = selector.findKey(request);

				if (regexRoute != null) {

					while (itr.hasNext()) {
						entry = itr.next();

						if (regexRoute.key.startsWith(entry.getKey())) {
							value = entry.getValue();
						}
					}

					if (value != null) {
						SettingsManager.sipLogger.finer(request, "ConfigPrefixMap.lookup()... Found value: " + value);
						break;
					} else {
						SettingsManager.sipLogger.finer(request, "ConfigPrefixMap.lookup()... No value found.");
					}
				}

			}

		} catch (Exception e) {
			if (SettingsManager.getSipLogger() != null) {
				SettingsManager.getSipLogger().logStackTrace(e);
			}
		}

		return value;
	}
}
