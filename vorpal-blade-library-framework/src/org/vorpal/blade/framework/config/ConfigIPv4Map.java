package org.vorpal.blade.framework.config;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import inet.ipaddr.IPAddressString;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigIPv4Map extends TranslationsMap {
	public AddressMap map = new AddressMap();

	public int size() {
		return map.size();
	}

	public Translation createTranslation(String key) {
		Translation t = new Translation();
		map.put(new IPAddressString(key).getAddress().toIPv4(), t);
		return t;
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {

			// jwm - multiple selectors
			for (Selector selector : this.selectors) {

				RegExRoute regexRoute = selector.findKey(request);
				if (regexRoute != null) {
					value = new Translation(map.get(new IPAddressString(regexRoute.key).getAddress().toIPv4()));
					
					//populate attributes for later
					value.getAttributes().putAll(regexRoute.attributes);
				}

				if (value != null)
					break;

			}

		} catch (Exception e) {
			if (SettingsManager.getSipLogger() == null) {
				e.printStackTrace();
			} else {
				SettingsManager.getSipLogger().logStackTrace(e);
			}

		}

		return value;
	}
}
