package org.vorpal.blade.test.config;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.Address;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.config.ConfigHashMap;
import org.vorpal.blade.framework.v2.config.ConfigPrefixMap;
import org.vorpal.blade.framework.v2.config.InetAddressKeyDeserializer;
import org.vorpal.blade.framework.v2.config.JsonAddressDeserializer;
import org.vorpal.blade.framework.v2.config.JsonAddressSerializer;
import org.vorpal.blade.framework.v2.config.JsonIPAddressDeserializer;
import org.vorpal.blade.framework.v2.config.JsonIPAddressSerializer;
import org.vorpal.blade.framework.v2.config.JsonUriDeserializer;
import org.vorpal.blade.framework.v2.config.JsonUriSerializer;
import org.vorpal.blade.framework.v2.config.RouterConfig;
import org.vorpal.blade.framework.v2.config.Selector;
import org.vorpal.blade.framework.v2.config.Translation;
import org.vorpal.blade.framework.v2.config.TranslationsMap;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.services.proxy.router.ProxyRouterConfigSample;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import inet.ipaddr.IPAddress;

public class ProxyRouterSXM extends RouterConfig implements Serializable {

	public ProxyRouterSXM() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);
		this.session = null;

		Selector caller = new Selector("caller", "From", SIP_ADDRESS_PATTERN, "${user}");
		caller.setDescription("caller's number");
		this.selectors.add(caller);

		Selector dialed = new Selector("dialed", "To", SIP_ADDRESS_PATTERN, "${user}");
		dialed.setDescription("dialed number");
		this.selectors.add(dialed);

		TranslationsMap callers = new ConfigHashMap();
		callers.setId("callers");
		callers.addSelector(caller);
		callers.setDescription("map of blocked callers");
		
		
		Translation t_alice = callers.createTranslation("19727536200").setId("19727536200").setRequestUri("sip:18009254733@gensip.occas.cv300-telematics.net:5061;transport=tls");


//		// if alice calls bob, shes get carol
//		// if alice calls carol, she gets bob
//		// if alice calls doug, it goes through
//		Translation a1 = callers.createTranslation("alice");
//		a1.setId("alice"); // don't think this is needed.
//		List<TranslationsMap> a1Maps = new LinkedList<>();
//		TranslationsMap a1PrefixMap = new ConfigPrefixMap();
//		a1PrefixMap.addSelector(dialed);
//		a1PrefixMap.createTranslation("bob").setId("bob").setRequestUri("sip:carol@vorpal.net");
//		a1PrefixMap.createTranslation("carol").setId("carol").setRequestUri("sip:bob@vorpal.net");
//		a1Maps.add(a1PrefixMap);
//		a1.setList(a1Maps);
//
//		// a real jerk from kansas city
//		Translation c1 = callers.createTranslation("18165551234");
//		c1.setId("18165551234"); // don't think this is needed.
//		c1.setRequestUri("sip:voicemail");
//		c1.setDescription("generally abusive; send all calls to voicemail unless the below numbers are dialed");
//		List<TranslationsMap> c1Maps = new LinkedList<>();
//		TranslationsMap c1PrefixMap = new ConfigPrefixMap();
//		c1PrefixMap.addSelector(dialed);
//		c1PrefixMap.createTranslation("1913").setId("1913").setRequestUri("sip:voicemail913")
//				.setDescription("for (913) area code");
//		c1PrefixMap.createTranslation("1913555").setId("1913555").setRequestUri("sip:voicemail913555")
//				.setDescription("for (913) 555 NPA");
//		c1PrefixMap.createTranslation("19135550001").setId("19135550001").setRequestUri("sip:voicemail9130001")
//				.setDescription("for (913)555-001 NXX");
//		c1Maps.add(c1PrefixMap);
//		c1.setList(c1Maps);
//
//		// a jilted lover from from sacramento
//		Translation c2 = callers.createTranslation("12795555678");
//		c2.setId("12795555678");
//		c2.setDescription("harrases a specific employee");
//		List<TranslationsMap> c2Maps = new LinkedList<>();
//		TranslationsMap c2HashMap = new ConfigHashMap();
//		c2HashMap.addSelector(dialed);
//		c2HashMap.createTranslation("15305559876").setId("15305559876");
//		c2Maps.add(c2HashMap);
//		c2.setList(c2Maps);

		this.maps.add(callers);
		this.plan.add(callers);

	}

	public static void main(String[] args) throws JsonProcessingException {

		ProxyRouterSXM configuration = new ProxyRouterSXM();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		mapper.registerModule(new SimpleModule().addDeserializer(URI.class, new JsonUriDeserializer()));
		mapper.registerModule(new SimpleModule().addDeserializer(Address.class, new JsonAddressDeserializer()));
		mapper.registerModule(new SimpleModule().addDeserializer(IPAddress.class, new JsonIPAddressDeserializer()));
		mapper.registerModule(new SimpleModule().addSerializer(URI.class, new JsonUriSerializer()));
		mapper.registerModule(new SimpleModule().addSerializer(Address.class, new JsonAddressSerializer()));
		mapper.registerModule(new SimpleModule().addSerializer(IPAddress.class, new JsonIPAddressSerializer()));
		mapper.registerModule(
				new SimpleModule().addKeyDeserializer(inet.ipaddr.Address.class, new InetAddressKeyDeserializer()));

		// Don't both to save attributes set to null.
		mapper.setSerializationInclusion(Include.NON_NULL);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);

		System.out.println(output);

	}

}
