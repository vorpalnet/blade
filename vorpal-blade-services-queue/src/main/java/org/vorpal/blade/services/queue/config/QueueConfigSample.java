package org.vorpal.blade.services.queue.config;

import org.vorpal.blade.framework.v2.config.ConfigHashMap;
import org.vorpal.blade.framework.v2.config.ConfigPrefixMap;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class QueueConfigSample extends QueueConfig {

	private static final long serialVersionUID = 1L;

	public QueueConfigSample() {

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);

		this.session = new SessionParameters();
		this.session.setExpiration(900);

		this.defaultRoute = new Translation();
		this.defaultRoute.setId("default").setDescription("no queue defined, simply passthru");

		this.addQueue("fast", new QueueAttributes() //
				.setPeriod(5 * 1000) // number of milliseconds
				.setRate(10) //
				.setRingPeriod(90) //
				.setRingDuration(60 * 1000) //
				.setAnnouncement("sip:ann1@192.168.1.227"));

		this.addQueue("medium", new QueueAttributes() //
				.setPeriod(15 * 1000) //
				.setRate(5) //
				.setRingPeriod(90) //
				.setRingDuration(60 * 1000) //
				.setAnnouncement("sip:ann1@192.168.1.227"));

		this.addQueue("slow", new QueueAttributes() //
				.setPeriod(30 * 1000) //
				.setRate(1) //
				.setRingPeriod(90) //
				.setRingDuration(60 * 1000));

		Selector toSelector = new Selector("toSelector", "To", SIP_ADDRESS_PATTERN, "${user}");
		toSelector.setDescription("The user part of the To header");
		this.selectors.add(toSelector);

		TranslationsMap botMap = new ConfigHashMap();
		botMap.id = "botmap";
		botMap.addSelector(toSelector);
		botMap.description = "Translations map using HashMap for text";
		botMap.createTranslation("bob").addAttribute("queue", "slow");
		this.maps.add(botMap);

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefixMap";
		prefixMap.addSelector(toSelector);
		prefixMap.description = "Translations map using PrefixMap for dialed numbers";
		prefixMap.createTranslation("1816555").addAttribute("queue", "slow");
		this.maps.add(prefixMap);

		this.plan.add(prefixMap);
		this.plan.add(botMap);

	}

}
