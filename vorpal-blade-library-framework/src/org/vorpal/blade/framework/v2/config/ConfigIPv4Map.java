package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import inet.ipaddr.IPAddressString;

@Deprecated
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigIPv4Map extends TranslationsMap implements Serializable{
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

			for (Selector selector : this.selectors) {
				RegExRoute regexRoute = selector.findKey(request);

				if (regexRoute != null) {
					value = map.get(new IPAddressString(regexRoute.key).getAddress().toIPv4());
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

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(e);
		}

		return value;
	}
}
