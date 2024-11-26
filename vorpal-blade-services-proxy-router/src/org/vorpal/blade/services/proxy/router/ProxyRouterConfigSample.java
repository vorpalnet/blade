package org.vorpal.blade.services.proxy.router;

import java.io.Serializable;

import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.RouterConfig;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.SessionParametersDefault;
import org.vorpal.blade.framework.config.Translation;
import org.vorpal.blade.framework.config.TranslationsMap;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class ProxyRouterConfigSample extends RouterConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	public ProxyRouterConfigSample() {
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();

		this.defaultRoute = new Translation();

		defaultRoute.setId("default");
		defaultRoute.setDescription("If no translation found, apply this default route.");
		defaultRoute.setRequestUri("sip:hold");

		Selector dialed = new Selector("dialed", "To", SIP_ADDRESS_PATTERN, "${user}");

		dialed.setDescription("dialed phone number");
		this.selectors.add(dialed);

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefix-map-1";
		prefixMap.addSelector(dialed);
		prefixMap.description = "Translations map for dialed number prefixes";
		prefixMap.createTranslation("1997").setRequestUri("sip:10.11.200.39:5060");
		prefixMap.createTranslation("19974388687").setRequestUri("sip:10.11.200.40:5060");

		this.maps.add(prefixMap);

		this.plan.add(prefixMap);

	}

}
