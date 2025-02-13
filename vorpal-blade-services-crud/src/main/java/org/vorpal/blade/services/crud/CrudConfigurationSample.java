package org.vorpal.blade.services.crud;

import org.vorpal.blade.framework.v2.config.ConfigPrefixMap;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogParameters;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class CrudConfigurationSample extends CrudConfiguration {
	public CrudConfigurationSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LogParameters.LoggingLevel.FINEST);
		this.defaultRoute = new Translation();
		this.defaultRoute.setId("default-route");
		Selector dialed = new Selector("ani", "From", SIP_ADDRESS_PATTERN, "${user}");
		dialed.setDescription("The user (dialed number) part of the To header");
		this.selectors.add(dialed);
		RuleSet ruleSet = new RuleSet();
		ruleSet.id = "rule1";
		Rule rule = new Rule();
		Update update = new Update("Request-URI", SIP_ADDRESS_PATTERN, "${proto}:carol@${host}:5060;${uriparams}");
		rule.update.add(update);
		Update toHeader = new Update("To", SIP_ADDRESS_PATTERN, "<${proto}:carol@${host}:5060;${uriparams}>");
		rule.update.add(toHeader);
		ruleSet.rules.add(rule);

		this.ruleSets.put(ruleSet.id, ruleSet);
		
		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefix-map";
		prefixMap.addSelector(dialed);
		prefixMap.description = "Translations map for dialed number prefixes";
		prefixMap.createTranslation("19974388689").addAttribute("ruleSet", ruleSet);
		prefixMap.createTranslation("alice").addAttribute("ruleSet", ruleSet.id);
		this.maps.add(prefixMap);
		this.plan.add(prefixMap);
	}
}
