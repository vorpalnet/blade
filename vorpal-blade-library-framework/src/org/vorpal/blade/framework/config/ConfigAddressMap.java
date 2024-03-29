package org.vorpal.blade.framework.config;

import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import inet.ipaddr.IPAddressString;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigAddressMap extends TranslationsMap {
	public AddressMap map = new AddressMap();

	public int size() {
		return map.size();
	}

	public Translation createTranslation(String key) {
		Translation t = new Translation();
		map.put(new IPAddressString(key).getAddress(), t);
		return t;
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {

			for (Selector selector : this.selectors) {

				RegExRoute regexRoute = selector.findKey(request);

				if (regexRoute != null && regexRoute.key != null) {
					value = new Translation(map.get(regexRoute.key));
					if (value.getAttributes() == null) {
						value.setAttributes(new HashMap<>());
					}
					if (value != null && regexRoute.attributes != null) {
						value.getAttributes().putAll(regexRoute.attributes);
					}
				}

				if (value != null)
					break;

			}

		} catch (Exception e) {
			if (SettingsManager.getSipLogger() != null) {
				SettingsManager.getSipLogger().logStackTrace(e);
			} else {
				e.printStackTrace();
			}
		}

		return value;
	}
}
