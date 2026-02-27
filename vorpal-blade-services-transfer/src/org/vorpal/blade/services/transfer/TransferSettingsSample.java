package org.vorpal.blade.services.transfer;

import java.util.List;

import org.vorpal.blade.framework.v2.analytics.AnalyticsTransferSample;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.ConfigHashMap;
import org.vorpal.blade.framework.v2.config.ConfigPrefixMap;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v2.transfer.TransferSettings;

public class TransferSettingsSample extends TransferSettings {

	private static final long serialVersionUID = 1L;

	public TransferSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);
		this.analytics = new AnalyticsTransferSample();

		this.session = new SessionParametersDefault();
		this.getSession().setExpiration(60);

		this.conferenceApp = null;

//		List<AttributeSelector> indexKeySelectors = this.getSession().getSessionSelectors();
//
//		AttributeSelector inbound = new AttributeSelector();
//		inbound.setId("inbound");
//		inbound.setDescription("Mark the session as inbound based on OSM-Features. No expression means no session key");
//		inbound.setAttribute("OSM-Features");
//		inbound.setPattern("^.*shuffleib.*$");
//		inbound.addAdditionalExpression("direction", "inbound");
//		indexKeySelectors.add(inbound);
//
//		AttributeSelector outbound = new AttributeSelector();
//		outbound.setId("outbound");
//		outbound.setDescription(
//				"Mark the session as outbound based on OSM-Features. No expression means no session key");
//		outbound.setAttribute("OSM-Features");
//		outbound.setPattern("^.*shuffleob.*$");
//		outbound.addAdditionalExpression("direction", "outbound");
//		indexKeySelectors.add(outbound);
//
//		AttributeSelector gucidSelector = new AttributeSelector();
//		gucidSelector.setId("gucidSelector");
//		gucidSelector.setDescription("Create index key based on the value of the Cisco-Gucid header");
//		gucidSelector.setAttribute("Cisco-Gucid");
//		gucidSelector.setPattern("^(?<gucid>.*)$");
//		gucidSelector.setExpression("${gucid}");
//		indexKeySelectors.add(gucidSelector);
//
//		AttributeSelector guuid = new AttributeSelector();
//		guuid.setId("guuidSelector");
//		guuid.setDescription("Create index key based on the value of the X-Genesys-CallUUID header");
//		guuid.setAttribute("X-Genesys-CallUUID");
//		guuid.setPattern("^(?<guuid>.*)$");
//		guuid.setExpression("${guuid}");
//		indexKeySelectors.add(guuid);

		this.transferAllRequests = true;

		// this.defaultTransferStyle = TransferStyle.blind;

//		this.preserveInviteHeaders.add("Cisco-Gucid");
//		this.preserveInviteHeaders.add("User-to-User");
		this.preserveReferHeaders.add("Referred-By");

//		Selector dialed = new Selector("dialed", "From", SIP_ADDRESS_PATTERN, "${user}");
//		dialed.setDescription("The user (dialed number) part of the From header");
//		this.selectors.add(dialed);

		TranslationsMap prefixMap = new ConfigPrefixMap();
//		prefixMap.id = "prefix-map";
//		prefixMap.addSelector(dialed);
//		prefixMap.description = "Translations map for dialed number prefixes";
//
//		prefixMap.createTranslation("bob").setId("bob").addAttribute("style", "blind");
//
//		prefixMap.createTranslation("1897").setId("t1").addAttribute("style",
//				TransferSettings.TransferStyle.blind.toString());
//		prefixMap.createTranslation("18974388687").setId("t2").addAttribute("style",
//				TransferSettings.TransferStyle.attended.toString());
//		prefixMap.createTranslation("18974388689").setId("t3").addAttribute("style",
//				TransferSettings.TransferStyle.conference.toString());

		TranslationsMap hashMap = new ConfigHashMap();
//		hashMap.id = "hash-map";
//		hashMap.addSelector(dialed);
//		hashMap.description = "Translations map for account names";
//		hashMap.createTranslation("bob").addAttribute("style", TransferSettings.TransferStyle.blind.toString());

		this.maps.add(prefixMap);
		this.maps.add(hashMap);
		this.plan.add(prefixMap);
		this.plan.add(hashMap);

	}

}
