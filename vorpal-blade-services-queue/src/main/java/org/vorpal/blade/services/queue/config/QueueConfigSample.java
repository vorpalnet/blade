package org.vorpal.blade.services.queue.config;

import org.vorpal.blade.framework.config.ConfigHashMap;
import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.SessionParameters;
import org.vorpal.blade.framework.config.Translation;
import org.vorpal.blade.framework.config.TranslationsMap;
import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class QueueConfigSample extends QueueConfig {

	public QueueConfigSample() {

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);

		this.session = new SessionParameters();
		this.session.setExpiration(900);

		this.defaultRoute = new Translation();
		this.defaultRoute.setId("default").setDescription("no queue defined, simply passthru");

//		this.addQueue("fast", new QueueAttributes() //
//				.setPeriod(500) //
//				.setRate(10) //
//				.setRingDuration(15) //
//				.addAnnouncement("sip:ann1@192.168.1.227"));
//
//		this.addQueue("medium", new QueueAttributes() //
//				.setPeriod(1000) //
//				.setRate(5) //
//				.setRingDuration(20) //
//				.addAnnouncement("sip:ann1@192.168.1.227") //
//				.addAnnouncement("sip:ann2@192.168.1.227"));

		this.addQueue("slow", new QueueAttributes() //
				.setPeriod(30 * 1000) //
				.setRate(1) //
				.setRingPeriod(1) //
				.setRingDuration(5) //
				.setAnnouncement("sip:carol@vorpal.net")
		);

		Selector toSelector = new Selector("toSelector", "To",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
				"${user}");
		toSelector.setDescription("The user part of the From header");
		this.selectors.add(toSelector);

		TranslationsMap botMap = new ConfigHashMap();
		botMap.id = "botmap";
		botMap.addSelector(toSelector);
		botMap.description = "Translations map using HashMap for text";
		botMap.createTranslation("bob").addAttribute("queue", "slow");

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefixMap";
		prefixMap.addSelector(toSelector);
		prefixMap.description = "Translations map using PrefixMap for dialed numbers";
		botMap.createTranslation("1997").addAttribute("queue", "slow");

		this.maps.add(prefixMap);
		this.maps.add(botMap);

		this.plan.add(prefixMap);
		this.plan.add(botMap);

	}

}
