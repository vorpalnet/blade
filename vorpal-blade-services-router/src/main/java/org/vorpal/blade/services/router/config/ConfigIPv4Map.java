package org.vorpal.blade.services.router.config;

import java.util.Iterator;
import java.util.Map.Entry;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import inet.ipaddr.Address;
import inet.ipaddr.IPAddressString;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ConfigIPv4Map extends TranslationsMap {
	public AddressMap map = new AddressMap();

	public Translation createTranslation(String key) {
		Translation t = new Translation();
		map.put(new IPAddressString(key).getAddress().toIPv4(), t);
		return t;
	}

	@Override
	public Translation lookup(SipServletRequest request) {
		Translation value = null;

		try {

			RegExRoute regexRoute = this.selector.findKey(request);
			if (regexRoute != null) {
				value = map.get(new IPAddressString(regexRoute.key).getAddress().toIPv4());
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
