package org.vorpal.blade.services.queue;

import org.vorpal.blade.framework.config.ConfigAddressMap;
import org.vorpal.blade.framework.config.ConfigHashMap;
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

		// Create some new queues

//		this.addQueue(new Queue().setId("fast").setPeriod(500).setRate(10));
		this.addQueue(new Queue().setId("medium").setPeriod(1000).setRate(5));
//		this.addQueue(new Queue().setId("slow").setPeriod(10000).setRate(1));

		// Add a sample selector for the Referred-By User
		Selector referredByUser = new Selector("referredByUser", "Referred-By",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
				"${user}");
		referredByUser.setDescription("The user part of the Referred-By header");
		this.selectors.add(referredByUser);

//		// Add a sample selector for the From User
//		Selector fromUser = new Selector("fromUser", "From",
//				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
//				"${user}");
//		fromUser.setDescription("The user part of the From header");
//		this.selectors.add(fromUser);
//
//		// Add a sample selector for IP Address in the Contact Header
//		Selector contactHost = new Selector("contactHost", "Contact",
//				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
//				"${host}");
//		contactHost.setDescription("The host part of the Contact header");
//		this.selectors.add(contactHost);

		TranslationsMap botMap = new ConfigHashMap();
		botMap.id = "botmap";
		botMap.addSelector(referredByUser);
		botMap.description = "Translations map using HashMap for text";
		botMap.createTranslation("voicebot").addAttribute("queue", "medium");

//		TranslationsMap addressMap = new ConfigAddressMap();
//		addressMap.id = "address-map";
//		addressMap.addSelector(contactHost);
//		addressMap.description = "Translations map for IP Addresses";

//		addressMap.createTranslation("10.0.1.101").addAttribute("queue", "medium");
//		addressMap.createTranslation("192.168.1.180").addAttribute("queue", "slow");

//		TranslationsMap prefixMap = new ConfigPrefixMap();
//		prefixMap.id = "prefix-map";
//		prefixMap.addSelector(fromUser);
//		prefixMap.description = "Translations map for phone number prefixes";

//		prefixMap.createTranslation("1997").addAttribute("queue", "medium");
//		prefixMap.createTranslation("19974388687").addAttribute("queue", "slow");

		this.maps.add(botMap);
//		this.maps.add(addressMap);
//		this.maps.add(prefixMap);

		this.plan.add(botMap);
//		this.plan.add(addressMap);
//		this.plan.add(prefixMap);

	}

}
