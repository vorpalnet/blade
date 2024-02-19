package org.vorpal.blade.services.transfer;

import org.vorpal.blade.framework.config.ConfigHashMap;
import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.TranslationsMap;
import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class TransferSettingsSample extends TransferSettings {

	private static final long serialVersionUID = 1L;

	public TransferSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINE);

		this.preserveInviteHeaders.add("Cisco-Gucid");
		this.preserveInviteHeaders.add("User-to-User");
		this.preserveReferHeaders.add("Referred-By");

		Selector dialed = new Selector("dialed", "From",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*[;]*(?<uriparams>[^>]*)[>;]*(?<addrparams>.*)",
				"${user}");

		dialed.setDescription("The user (dialed number) part of the From header");
		this.selectors.add(dialed);

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefix-map";
		prefixMap.addSelector(dialed);
		prefixMap.description = "Translations map for dialed number prefixes";
		prefixMap.createTranslation("1997").setId("t1").addAttribute("style",
				TransferSettings.TransferStyle.blind.toString());
		prefixMap.createTranslation("19974388687").setId("t2").addAttribute("style",
				TransferSettings.TransferStyle.attended.toString());
		prefixMap.createTranslation("19974388689").setId("t3").addAttribute("style",
				TransferSettings.TransferStyle.conference.toString());

		TranslationsMap hashMap = new ConfigHashMap();
		hashMap.id = "hash-map";
		hashMap.addSelector(dialed);
		hashMap.description = "Translations map for account names";
		hashMap.createTranslation("bob").addAttribute("style", TransferSettings.TransferStyle.blind.toString());

		this.maps.add(prefixMap);
		this.maps.add(hashMap);
		this.plan.add(prefixMap);
		this.plan.add(hashMap);

	}

}
