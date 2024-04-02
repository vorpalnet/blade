package org.vorpal.blade.framework.config;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.servlet.sip.SipServletRequest;

import org.apache.commons.collections4.trie.PatriciaTrie;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMap extends TranslationsMap {

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

				if (regexRoute != null && regexRoute.key != null && regexRoute.attributes != null) {

					while (itr.hasNext()) {
						entry = itr.next();

						if (regexRoute != null && regexRoute.key != null) {
							value = new Translation(map.get(regexRoute.key));
							
// Skip this nonsense for now...							
//							if (value.getAttributes() == null) {
//								value.setAttributes(new HashMap<>());
//							}
//							if (value != null && regexRoute.attributes != null) {
//								value.getAttributes().putAll(regexRoute.attributes);
//							}
							
							
						}
					}

					if (value != null) {
						SettingsManager.sipLogger.finer(request, "ConfigPrefixMap Route found id: " + value.getId()
								+ ", description=" + value.getDescription());
						break;
					} else {
						SettingsManager.sipLogger.finer(request, "ConfigPrefixMap Route not found");
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
