package org.vorpal.blade.framework.v2.config;

import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigPrefixMap extends TranslationsMap {

//	private static Logger sipLogger;

	public HashMap<String, Translation> map = new HashMap<>();

//	public PatriciaTrie<Translation> map = new PatriciaTrie<>();

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
					for (int i = regexRoute.key.length(); i > 0; --i) {
						substring = regexRoute.key.substring(0, i);

						value = map.get(substring);
						if (value != null) {
							break;
						}

					}

					if (value != null) {
						value = new Translation(value);
						if (regexRoute.attributes != null && regexRoute.attributes.size() > 0) {
							value.getAttributes().putAll(regexRoute.attributes);
						}
						break;
					}

				}

			}

		} catch (Exception e) {
			Callflow.getSipLogger().logStackTrace(e);
		}

		return value;
	}

}
