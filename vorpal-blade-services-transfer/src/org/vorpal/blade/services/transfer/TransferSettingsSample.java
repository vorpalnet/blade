package org.vorpal.blade.services.transfer;

import org.vorpal.blade.framework.config.ConfigHashMap;
import org.vorpal.blade.framework.config.ConfigPrefixMap;
import org.vorpal.blade.framework.config.Selector;
import org.vorpal.blade.framework.config.TranslationsMap;
import org.vorpal.blade.framework.transfer.TransferCondition;

public class TransferSettingsSample extends TransferSettings {

	public TransferSettingsSample() {

//		// Perform transfer if "OSM-Features" header contains "transfer"
//		Selector s1 = new Selector();
//		s1.setId("osmTransfer");
//		s1.setDescription("select on the OSM-Features header");
//		s1.setAttribute("OSM-Features");
//		s1.setPattern("^.*$");
//		s1.setExpression("$1");
//			
//		TranslationsMap m1 = new ConfigHashMap();
//		m1.id = "header-map";
//		m1.description = "match on any of these values";
//		m1.selector = s1;		
//		m1.createTranslation("transfer");
//
//		Selector dialed = new Selector("dialed", "To", "^(sips?):([^@]+)(?:@(.+))?$", "$2");
//		dialed.setDescription("select on the dialed phone number");
//		performTransfer.selectors.add(dialed);		
//		
//		TranslationsMap prefixMap = new ConfigPrefixMap();
//		prefixMap.id = "prefix-map-1";
//		prefixMap.selector = dialed;
//		prefixMap.description = "Translations map for dialed number prefixes";	
//		
//		prefixMap.createTranslation("19951").setRequestUri("sip:10.29.68.26:5060").setDescription("CL2 DEV");
//		prefixMap.createTranslation("19971").setRequestUri("sip:10.86.34.184:5060").setDescription("CL1 Dev2");
//		prefixMap.createTranslation("19954").setRequestUri("sip:10.29.82.110:5060").setDescription("CL2 STG");
//
//		performTransfer.plan.add(prefixMap);

		this.setTransferAllRequests(false);
		this.setDefaultTransferStyle(TransferSettings.TransferStyle.blind);

		TransferCondition tc1 = new TransferCondition();
		tc1.setStyle(TransferStyle.blind);
		tc1.getCondition().addComparison("OSM-Features", "includes", "transfer");
		this.getTransferConditions().add(tc1);

		TransferCondition tc3 = new TransferCondition();
		tc3.setStyle(TransferStyle.blind);
		tc3.getCondition().addComparison("Refer-To", "user", "carol");
		this.getTransferConditions().add(tc3);

		TransferCondition tc2 = new TransferCondition();
		tc2.setStyle(TransferStyle.blind);
		tc2.getCondition().addComparison("Refer-To", "matches", ".*sip:1996.*");
		this.getTransferConditions().add(tc2);

		preserveInviteHeaders.add("Cisco-Gucid");
		preserveInviteHeaders.add("User-to-User");
		preserveReferHeaders.add("Referred-By");

	}

}
