package org.vorpal.blade.services.queue;

import org.vorpal.blade.framework.config.ConfigAddressMap;
import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.TranslationsMap;
import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class QueueConfigSample extends QueueConfig {

	public QueueConfigSample() {

		// Add sample logging parameters
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);

		// Add a sample selector for the From User
		Selector fromUser = new Selector("fromUser", "From",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
				"${user}");
		fromUser.setDescription("The user part of the From header");
		this.selectors.add(fromUser);

		// Add a sample selector for IP Address in the Contact Header
		Selector contactHost = new Selector("contactHost", "Contact",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
				"${host}");
		contactHost.setDescription("The host part of the Contact header");
		this.selectors.add(contactHost);

		Queue networkQueue1 = new Queue().setId("25tps").setPeriod(1000).setRate(25);
		this.addQueue(networkQueue1);

		Queue networkQueue2 = new Queue().setId("6cpm").setPeriod(10000).setRate(1);
		this.addQueue(networkQueue2);

		TranslationsMap addressMap = new ConfigAddressMap();
		addressMap.id = "address-map";
		addressMap.addSelector(contactHost);
		addressMap.description = "Translations map for IP Addresses";

		addressMap.createTranslation("10.0.1.101").addAttribute("queue", "25tps");
		addressMap.createTranslation("192.168.1.180").addAttribute("queue", "6cpm");

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefix-map";
		prefixMap.addSelector(fromUser);
		prefixMap.description = "Translations map for phone number prefixes";

		prefixMap.createTranslation("1997").addAttribute("queue", "25tps");
		prefixMap.createTranslation("19974388687").addAttribute("queue", "6cpm");

		this.maps.add(addressMap);
		this.maps.add(prefixMap);

		this.plan.add(addressMap);
		this.plan.add(prefixMap);

	}

}
