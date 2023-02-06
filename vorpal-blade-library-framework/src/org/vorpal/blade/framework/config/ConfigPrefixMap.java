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

		Iterator<Entry<String, Translation>> itr = map.entrySet().iterator();
		Entry<String, Translation> entry = null;
//		Entry<String, Translation> previous = null;

		try {
			RegExRoute regexRoute = this.selector.findKey(request);

			while (itr.hasNext()) {
//				previous = entry;
				entry = itr.next();

				if (regexRoute.key.startsWith(entry.getKey())) {
					value = entry.getValue();
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
