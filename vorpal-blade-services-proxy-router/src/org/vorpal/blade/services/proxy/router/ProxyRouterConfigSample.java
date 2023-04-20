package org.vorpal.blade.services.proxy.router;

import java.io.Serializable;

import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.RouterConfig;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.TranslationsMap;

public class ProxyRouterConfigSample extends RouterConfig implements Serializable {

	public ProxyRouterConfigSample() {

		defaultRoute.setId("default");
		defaultRoute.setDescription("If no translation found, apply this default route.");
		defaultRoute.setRequestUri("sip:hold");

//		Selector recorddn = new Selector("recorddn", "Content", "recorddn=(?<recorddn>[0-9]+)", "${recorddn}");
//		recorddn.setDescription("SIPREC Record Dialed Number");
//		this.selectors.add(recorddn);

		Selector dialed = new Selector("dialed", "To",
				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
				"${user}");
		dialed.setDescription("dialed phone number");
		this.selectors.add(dialed);

//		Selector caller = new Selector("caller", "From",
//				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
//				"${user}");
//		caller.setDescription("caller's phone number");
//		this.selectors.add(caller);

//		Selector origin = new Selector("origin", "Remote-IP", "(?<remote>.*)", "${remote}");
//		origin.setDescription("IP address of the upstream client");
//		this.selectors.add(origin);

//		Selector destination = new Selector("destination", "Request-URI",
//				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
//				"${host}");
//		destination.setDescription("Host or IP Address of the downstream server");
//		this.selectors.add(destination);

//		// Create a selector and Translations Hashmap for user accounts.
//		Selector account = new Selector("account", "To",
//				"(?:\"(?<name>.*)\" )*[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;]*)(?:[:](?<port>[0-9]+))*(?:[;](?<uriparams>[^>]*))*[>]*[;]*(?<addrparams>.*)",
//				"${user}@${host}");
//		account.setDescription("User's account name. i.e.: bob@vorpal.net");
//		this.selectors.add(account);
//		TranslationsMap accountMap = new ConfigHashMap();
//		accountMap.id = "account-map";
//		accountMap.description = "Translations map for user accounts";
//		accountMap.selector = account;
//		this.maps.add(accountMap);
//		accountMap.createTranslation("transferor@vorpal.net").setRequestUri("sip:test1");

//		TranslationsMap addressMap = new ConfigAddressMap();
//		addressMap.id = "address-map-1";
//		addressMap.description = "Translations Map for Remote IP addresses";
//		addressMap.selector = origin;

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefix-map-1";
		prefixMap.addSelector(dialed);
		prefixMap.description = "Translations map for dialed number prefixes";
		prefixMap.createTranslation("1997").setRequestUri("sip:10.11.200.39:5060");
		prefixMap.createTranslation("19974388687").setRequestUri("sip:10.11.200.40:5060");

//		TranslationsMap prefixMapRecorddn = new ConfigPrefixMap();
//		prefixMapRecorddn.id = "prefix-map-recorddn";
//		prefixMapRecorddn.selector = dialed;
//		prefixMapRecorddn.description = "Translations map for SIPREC Record Dialed Number.";

//		this.maps.add(addressMap);
		this.maps.add(prefixMap);

//		addressMap.createTranslation("127.0.0.1").setRequestUri("sip:localhost:5060");
//		addressMap.createTranslation("10.173.101.120").setId("STG_CL1_ATT_IB").setRequestUri("sip:10.173.165.70:5060");
//		addressMap.createTranslation("10.173.101.121").setId("STG_CL1_VZB_IB").setRequestUri("sip:10.173.165.69:5060");
//		addressMap.createTranslation("10.173.101.86").setId("STG_CL2_ATT_IB").setRequestUri("sip:10.173.165.128:5060");
//		addressMap.createTranslation("10.173.101.87").setId("STG_CL2_VZB_IB").setRequestUri("sip:10.173.165.127:5060");
//		addressMap.createTranslation("10.87.152.172").setRequestUri("sip:10.204.67.59:5060")
//				.setDescription("On Net TFN Test");
//		addressMap.createTranslation("10.87.152.173").setRequestUri("sip:10.204.67.59:5060")
//				.setDescription("On Net TFN Test");
//		addressMap.createTranslation("10.204.67.59").setRequestUri("sip:10.204.67.59:5060")
//				.setDescription("On Net TFN Test");
//		addressMap.createTranslation("10.204.67.60").setRequestUri("sip:10.204.67.59:5060")
//				.setDescription("On Net TFN Test");
//		addressMap.createTranslation("10.204.67.60").setRequestUri("sip:10.173.165.150:5060")
//				.setDescription("CL1 DEV 2 OB");
//		addressMap.createTranslation("10.28.194.166").setRequestUri("sip:10.173.165.140:5060")
//				.setDescription("CL2 DEV OB");
//		addressMap.createTranslation("10.29.68.26").setRequestUri("sip:10.173.165.140:5060")
//				.setDescription("CL2 DEV OB");
//		addressMap.createTranslation("10.87.152.172").setRequestUri("sip:10.173.165.152:5060")
//				.setDescription("CL1 STG OB");
//		addressMap.createTranslation("10.87.152.173").setRequestUri("sip:10.173.165.152:5060")
//				.setDescription("CL1 STG OB");
//		addressMap.createTranslation("10.204.67.59").setRequestUri("sip:10.173.165.152:5060")
//				.setDescription("CL1 STG OB");
//		addressMap.createTranslation("10.204.67.60").setRequestUri("sip:10.173.165.152:5060")
//				.setDescription("CL1 STG OB");
//		addressMap.createTranslation("10.28.82.132").setRequestUri("sip:10.173.165.142:5060")
//				.setDescription("CL2 STG OB");
//		addressMap.createTranslation("10.28.201.244").setRequestUri("sip:10.173.165.142:5060")
//				.setDescription("CL2 STG OB");
//		addressMap.createTranslation("10.29.82.110").setRequestUri("sip:10.173.165.142:5060")
//				.setDescription("CL2 STG OB");
//		addressMap.createTranslation("10.29.194.20").setRequestUri("sip:10.173.165.142:5060")
//				.setDescription("CL2 STG OB");
//
//
//		prefixMap.createTranslation("19951").setRequestUri("sip:10.29.68.26:5060").setDescription("CL2 DEV");
//		prefixMap.createTranslation("19971").setRequestUri("sip:10.86.34.184:5060").setDescription("CL1 Dev2");
//		prefixMap.createTranslation("19954").setRequestUri("sip:10.29.82.110:5060").setDescription("CL2 STG");
//		Translation tt = prefixMap.createTranslation("19974").setRequestUri("sip:10.204.67.59:5060").setDescription("CL1 STG");	
//		tt.list = new LinkedList<TranslationsMap>();
//		tt.list.add(addressMap);

//		this.plan.add(accountMap);
//		this.plan.add(addressMap);
		this.plan.add(prefixMap);

	}

}
