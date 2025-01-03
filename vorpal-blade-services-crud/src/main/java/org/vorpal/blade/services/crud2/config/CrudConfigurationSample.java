package org.vorpal.blade.services.crud2.config;

import org.vorpal.blade.framework.v2.config.ConfigPrefixMap;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.services.crud.RuleSet;
import org.vorpal.blade.services.crud2.rules.Create;
import org.vorpal.blade.services.crud2.rules.Rule;

// jeff - pretty sure this is garbage code, delete it.

public class CrudConfigurationSample extends CrudConfiguration {

	public CrudConfigurationSample() {

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);

		Selector dialed = new Selector("ani", "From", this.SIP_ADDRESS_PATTERN, "${fromUser}");

		dialed.setDescription("The user part of the From header");
		this.selectors.add(dialed);

		// RuleSets, Rules

		RuleSet ruleSet = new RuleSet();

		Rule create = new Create();

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.setId("prefix-map");
		prefixMap.addSelector(dialed);
		prefixMap.description = "Translations map for dialed number prefixes";
		prefixMap.createTranslation("bob").addAttribute("ruleSet", ruleSet.id).addAttribute("toPort", "5060");

		this.maps.add(prefixMap);
		this.plan.add(prefixMap);

	}

}
