package org.vorpal.blade.services.crud;

import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.Translation;
import org.vorpal.blade.framework.config.TranslationsMap;
import org.vorpal.blade.framework.logging.LogParameters;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class CrudConfigurationSample extends CrudConfiguration {
   public CrudConfigurationSample() {
      this.logging = new LogParametersDefault();
      this.logging.setLoggingLevel(LogParameters.LoggingLevel.FINEST);
      this.defaultRoute = new Translation();
      this.defaultRoute.setId("default-route");
      Selector dialed = new Selector("ani", "From", "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*[;]*(?<uriparams>[^>]*)[>;]*(?<addrparams>.*)", "${user}");
      dialed.setDescription("The user (dialed number) part of the To header");
      this.selectors.add(dialed);
      RuleSet ruleSet = new RuleSet();
      ruleSet.id = "rule1";
      Rule rule = new Rule();
      Update update = new Update("Request-URI", "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*[;]*(?<uriparams>[^>]*)[>;]*(?<addrparams>.*)", "${proto}:carol@${host}:5060;${uriparams}");
      rule.update.add(update);
      Update toHeader = new Update("To", "(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*)(?:[:](?<port>[0-9]+))*[;]*(?<uriparams>[^>]*)[>;]*(?<addrparams>.*)", "<${proto}:carol@${host}:5060;${uriparams}>");
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
