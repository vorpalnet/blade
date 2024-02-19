package org.vorpal.blade.framework.config;

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

			// jwm - multiple selectors

			SettingsManager.sipLogger.finer(request,
					"ConfigAddressMap.lookup() selectors size: " + this.selectors.size());

			for (Selector selector : this.selectors) {

				RegExRoute regexRoute = selector.findKey(request);

				SettingsManager.sipLogger.finer(request, "ConfigAddressMap.lookup() regexRoute: " + regexRoute);

				if (regexRoute != null) {
					SettingsManager.sipLogger.finer(request,
							"ConfigAddressMap.lookup() regexRoute.key: " + regexRoute.key);

					value = new Translation(map.get(new IPAddressString(regexRoute.key).getAddress()));
					
					//populate attributes for later
					value.getAttributes().putAll(regexRoute.attributes);

					SettingsManager.sipLogger.finer(request, "ConfigAddressMap.lookup() value: " + value);
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
