package org.vorpal.blade.services.router.config;

import java.util.LinkedList;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.InetAddressKeyDeserializer;
import org.vorpal.blade.framework.config.JsonAddressDeserializer;
import org.vorpal.blade.framework.config.JsonAddressSerializer;
import org.vorpal.blade.framework.config.JsonIPAddressDeserializer;
import org.vorpal.blade.framework.config.JsonIPAddressSerializer;
import org.vorpal.blade.framework.config.JsonUriDeserializer;
import org.vorpal.blade.framework.config.JsonUriSerializer;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

public class RouterSettings {

	public LinkedList<Selector> selectors = new LinkedList<>();
	public LinkedList<TranslationsMap> maps = new LinkedList<>();
	public LinkedList<TranslationsMap> plan = new LinkedList<>();
	public Translation defaultRoute = new Translation();

	public RouterSettings() {

		defaultRoute.id = "default";
		defaultRoute.description = "If no translation found, apply default route.";
		defaultRoute.requestUri = "sip:uas;status=404";

		Selector toUser = new Selector("to-user", "To", "^(sips?):([^@]+)(?:@(.+))?$", "$2");
		this.selectors.add(toUser);

		Selector remoteIp = new Selector("remote-ip", "Remote-IP", "^(.*)$", "$1");
		this.selectors.add(remoteIp);

		TranslationsMap addressMap = new ConfigAddressMap();
		addressMap.id = "address-map-1";
		addressMap.description = "Translations Map for Remote IP addresses";
		addressMap.selector = remoteIp;

		TranslationsMap prefixMap = new ConfigPrefixMap();
		prefixMap.id = "prefix-map-1";
		prefixMap.selector = toUser;
		prefixMap.description = "Translations map for dialed number prefixes";

		this.maps.add(addressMap);
		this.maps.add(prefixMap);

		addressMap.createTranslation("127.0.0.1").setRequestUri("sip:localhost:5060");

		addressMap.createTranslation("10.173.101.120").setId("STG_CL1_ATT_IB").setRequestUri("sip:10.173.165.70:5060");
		addressMap.createTranslation("10.173.101.121").setId("STG_CL1_VZB_IB").setRequestUri("sip:10.173.165.69:5060");
		addressMap.createTranslation("10.173.101.86").setId("STG_CL2_ATT_IB").setRequestUri("sip:10.173.165.128:5060");
		addressMap.createTranslation("10.173.101.87").setId("STG_CL2_VZB_IB").setRequestUri("sip:10.173.165.127:5060");
		addressMap.createTranslation("10.87.152.172").setRequestUri("sip:10.204.67.59:5060")
				.setDescription("On Net TFN Test");
		addressMap.createTranslation("10.87.152.173").setRequestUri("sip:10.204.67.59:5060")
				.setDescription("On Net TFN Test");
		addressMap.createTranslation("10.204.67.59").setRequestUri("sip:10.204.67.59:5060")
				.setDescription("On Net TFN Test");
		addressMap.createTranslation("10.204.67.60").setRequestUri("sip:10.204.67.59:5060")
				.setDescription("On Net TFN Test");
		addressMap.createTranslation("10.204.67.60").setRequestUri("sip:10.173.165.150:5060")
				.setDescription("CL1 DEV 2 OB");
		addressMap.createTranslation("10.28.194.166").setRequestUri("sip:10.173.165.140:5060")
				.setDescription("CL2 DEV OB");
		addressMap.createTranslation("10.29.68.26").setRequestUri("sip:10.173.165.140:5060")
				.setDescription("CL2 DEV OB");
		addressMap.createTranslation("10.87.152.172").setRequestUri("sip:10.173.165.152:5060")
				.setDescription("CL1 STG OB");
		addressMap.createTranslation("10.87.152.173").setRequestUri("sip:10.173.165.152:5060")
				.setDescription("CL1 STG OB");
		addressMap.createTranslation("10.204.67.59").setRequestUri("sip:10.173.165.152:5060")
				.setDescription("CL1 STG OB");
		addressMap.createTranslation("10.204.67.60").setRequestUri("sip:10.173.165.152:5060")
				.setDescription("CL1 STG OB");
		addressMap.createTranslation("10.28.82.132").setRequestUri("sip:10.173.165.142:5060")
				.setDescription("CL2 STG OB");
		addressMap.createTranslation("10.28.201.244").setRequestUri("sip:10.173.165.142:5060")
				.setDescription("CL2 STG OB");
		addressMap.createTranslation("10.29.82.110").setRequestUri("sip:10.173.165.142:5060")
				.setDescription("CL2 STG OB");
		addressMap.createTranslation("10.29.194.20").setRequestUri("sip:10.173.165.142:5060")
				.setDescription("CL2 STG OB");

		prefixMap.createTranslation("19951").setRequestUri("sip:10.29.68.26:5060").setDescription("CL2 DEV");
		prefixMap.createTranslation("19971").setRequestUri("sip:10.86.34.184:5060").setDescription("CL1 Dev2");
		prefixMap.createTranslation("19954").setRequestUri("sip:10.29.82.110:5060").setDescription("CL2 STG");
		prefixMap.createTranslation("19974").setRequestUri("sip:10.204.67.59:5060").setDescription("CL1 STG");

		this.plan.add(addressMap);
		this.plan.add(prefixMap);
	}

	public Boolean applyRoutes(SipServletRequest outboundRequest) {
		Boolean success = false;

		Translation t;
		for (TranslationsMap map : plan) {

			t = map.applyTranslations(outboundRequest);

			if (t != null) {
				success = true;
				break;
			}
		}

		return success;
	}

}
