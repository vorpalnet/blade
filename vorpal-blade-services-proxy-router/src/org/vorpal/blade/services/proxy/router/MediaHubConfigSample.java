package org.vorpal.blade.services.proxy.router;

import java.io.Serializable;

import org.vorpal.blade.framework.config.ConfigHashMap;
import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.RouterConfig;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.TranslationsMap;

/**
 * Creates a sample configuration file for the MediaHub. This is based off the
 * Vorpal:BLADE RouterConfig class. For the MediaHub application, the config
 * should be broken into two pieces. Global routes should appear in
 * "<domain>/config/custom/vorpal/mediahub.json". Static routes specific to
 * 'engine1' should appear in
 * "<domain>/config/custom/vorpal/server/engine1/mediahub.json".
 * 
 * @author Jeff McDonald
 */
public class MediaHubConfigSample extends RouterConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	public MediaHubConfigSample() {

		defaultRoute.setId("default");
		defaultRoute.setDescription("If no translation found, apply this default route.");
		defaultRoute.setRequestUri("sip:hold");

		Selector user = new Selector("user", "To",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?![+\\d])(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
				"${user}");
		user.setDescription("User account, not a phone number");
		this.selectors.add(user);

		Selector recorddn = new Selector("recorddn", "Content", "recorddn=(?<recorddn>[0-9]+)", "${recorddn}");
		recorddn.setDescription("SIPREC Record Dialed Number");
		this.selectors.add(recorddn);

		Selector dialed = new Selector("dialed", "To",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:[+]*(?<phone>[\\d]+)@)+(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
				"${user}");
		dialed.setDescription("dialed phone number");
		this.selectors.add(dialed);

		TranslationsMap hashMap = new ConfigHashMap();
		hashMap.id="user-hash-map";
		hashMap.addSelector(user);
		hashMap.description="Translations map for usernames, not phone numbers";
		hashMap.createTranslation("alice").setRequestUri("sip:10.11.200.55:5060").setDescription("matches any user named 'alice'.");
		hashMap.createTranslation("bob").setRequestUri("sip:10.11.200.56:5060").setDescription("matches any user named 'bob'.");
		
		
		
		
		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefix-map-dialed";
		prefixMap.addSelector(dialed);
		prefixMap.description = "Translations map for dialed number prefixes";
		prefixMap.createTranslation("1997").setRequestUri("sip:10.11.200.39:5060")
				.setDescription("Phone numbers starting with 1997").addAttribute("stripXML", true);
		;
//		Translation t87 = prefixMap.createTranslation("19974388687").setRequestUri("sip:10.11.200.40:5060");
		//

		TranslationsMap prefixMapRecorddn = new ConfigPrefixMap();
		prefixMapRecorddn.id = "prefix-map-recorddn";
		prefixMapRecorddn.addSelector(recorddn);
		prefixMapRecorddn.description = "Translations map for SIPREC Record Dialed Number.";
		prefixMapRecorddn.createTranslation("1998").setId("1998").setDescription("Phone numbers starting with 1998")
				.setRequestUri("sip:10.11.200.98:5060");
		prefixMapRecorddn.createTranslation("19984380001").setId("19984380001").setRequestUri("sip:10.11.200.99:5060")
				.addAttribute("stripXML", true);

		this.maps.add(prefixMapRecorddn);
		this.maps.add(prefixMap);

		this.plan.add(prefixMapRecorddn);
		this.plan.add(prefixMap);

	}

}
