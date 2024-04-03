package org.vorpal.blade.framework.config;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.servlet.sip.SipServletRequest;

import org.apache.commons.collections4.trie.PatriciaTrie;
import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMap extends TranslationsMap {

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

		Entry<String, Translation> entry = null;

		try {

			for (Selector selector : this.selectors) {
				Iterator<Entry<String, Translation>> itr = map.entrySet().iterator();

				RegExRoute regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					while (itr.hasNext()) {
						entry = itr.next();

						if (regexRoute.key.startsWith(entry.getKey())) {
							value = entry.getValue();
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
			}

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

		return value;
	}
}
