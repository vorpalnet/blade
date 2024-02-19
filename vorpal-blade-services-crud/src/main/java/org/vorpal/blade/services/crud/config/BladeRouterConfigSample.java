
package org.vorpal.blade.services.crud.config;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.InetAddressKeyDeserializer;
import org.vorpal.blade.framework.config.JsonAddressDeserializer;
import org.vorpal.blade.framework.config.JsonAddressSerializer;
import org.vorpal.blade.framework.config.JsonIPAddressDeserializer;
import org.vorpal.blade.framework.config.JsonIPAddressSerializer;
import org.vorpal.blade.framework.config.JsonIPv4AddressDeserializer;
import org.vorpal.blade.framework.config.JsonIPv4AddressSerializer;
import org.vorpal.blade.framework.config.JsonIPv6AddressDeserializer;
import org.vorpal.blade.framework.config.JsonIPv6AddressSerializer;
import org.vorpal.blade.framework.config.JsonSipUriDeserializer;
import org.vorpal.blade.framework.config.JsonSipUriSerializer;
import org.vorpal.blade.framework.config.JsonUriDeserializer;
import org.vorpal.blade.framework.config.JsonUriSerializer;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.kjetland.jackson.jsonSchema.JsonSchemaConfig;
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;
import com.kjetland.jackson.jsonSchema.SubclassesResolver;
import com.kjetland.jackson.jsonSchema.SubclassesResolverImpl;

import inet.ipaddr.IPAddress;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv6.IPv6Address;

public class BladeRouterConfigSample {

	public static ObjectMapper mapper;

	public static void main(String[] args) throws JsonGenerationException, JsonMappingException, IOException {

		BladeRouterConfig<BladeRuleSet> config = new BladeRouterConfig<BladeRuleSet>();

		BladeRuleSet ruleSet = new BladeRuleSet();

		BladeCreate create = new BladeCreate();
		create.component = "To";
		create.value = "sip:alice@vorpal.net";
		ruleSet.rules.add(create);

		BladeRead read = new BladeRead();
		read.component = "From";
		read.expression = "(?<from>.*)";
		ruleSet.rules.add(read);

		BladeUpdate update = new BladeUpdate();
		update.component = "Request-URI";
		update.expression = "(?<ruri>.*)";
		ruleSet.rules.add(update);

		BladeDelete delete = new BladeDelete();
		delete.component = "User-Agent";

		BladeTranslation<BladeRuleSet> defaultTranslation = new BladeTranslation<>();
		defaultTranslation.id = "default";
		defaultTranslation.route = ruleSet;

		BladeSelector selector = new BladeSelector();
		selector.id = "toSelector";
		selector.attribute = "To";
		selector.pattern = "(?<to>.*)";
		selector.expression = "${to}";
		config.selectors.add(selector);

		BladeTranslationsMap<BladeRuleSet> tMap = new BladeConfigPrefixMap<BladeRuleSet>();
		config.maps.add(tMap);
		config.plan.add(tMap);

		mapper = new ObjectMapper();

		mapper.registerModule(new SimpleModule()//
				.addSerializer(URI.class, new JsonUriSerializer()));
		mapper.registerModule(new SimpleModule()//
				.addDeserializer(URI.class, new JsonUriDeserializer()));

		// SipURI
		mapper.registerModule(new SimpleModule()//
				.addSerializer(SipURI.class, new JsonSipUriSerializer()));
		mapper.registerModule(new SimpleModule()//
				.addDeserializer(SipURI.class, new JsonSipUriDeserializer()));

		// Address
		mapper.registerModule(new SimpleModule()//
				.addSerializer(Address.class, new JsonAddressSerializer()));
		mapper.registerModule(new SimpleModule()//
				.addDeserializer(Address.class, new JsonAddressDeserializer()));

		// IPAddress
		mapper.registerModule(new SimpleModule()//
				.addSerializer(IPAddress.class, new JsonIPAddressSerializer()));
		mapper.registerModule(new SimpleModule()//
				.addDeserializer(IPAddress.class, new JsonIPAddressDeserializer()));

		// IPv4Address
		mapper.registerModule(new SimpleModule()//
				.addSerializer(IPv4Address.class, new JsonIPv4AddressSerializer()));
		mapper.registerModule(new SimpleModule()//
				.addDeserializer(IPv4Address.class, new JsonIPv4AddressDeserializer()));

		// IPv6Address
		mapper.registerModule(new SimpleModule()//
				.addSerializer(IPv6Address.class, new JsonIPv6AddressSerializer()));
		mapper.registerModule(new SimpleModule()//
				.addDeserializer(IPv6Address.class, new JsonIPv6AddressDeserializer()));

		mapper.registerModule(new SimpleModule()//
				.addKeyDeserializer(inet.ipaddr.Address.class, new InetAddressKeyDeserializer()));

		// Don't bother to save attributes set to null.
		mapper.setSerializationInclusion(Include.NON_NULL);

		mapper.configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false);


		try {
			printSchema(config);
		} catch (Exception e) {
			System.out.println(e);
		}

		try {
			printJson(config);
		} catch (Exception e) {
			System.out.println(e);
		}

		
		
	}

	public static void printSchema(BladeRouterConfig<BladeRuleSet> _config)
			throws JsonGenerationException, JsonMappingException, IOException {

		SubclassesResolver resolver = new SubclassesResolverImpl()
				.withClassesToScan(Arrays.asList(_config.getClass().getName()));

		JsonSchemaConfig config = JsonSchemaConfig.html5EnabledSchema().withSubclassesResolver(resolver);
		JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(mapper, config);
		JsonNode jsonSchema = jsonSchemaGenerator.generateJsonSchema(_config.getClass());
//		File schemaFile = new File(schemaPath.toString() + "/" + servletContextName + ".jschema");
		mapper.writerWithDefaultPrettyPrinter().writeValue(System.out, jsonSchema);

	}

	public static void printJson(BladeRouterConfig<BladeRuleSet> _config)
			throws JsonGenerationException, JsonMappingException, IOException {

		System.out.println(".........");
		System.out.println(".........");
		System.out.println(".........");
		System.out.println(".........");

		mapper.writerWithDefaultPrettyPrinter().writeValue(System.out, _config);

		System.out.println(".........");
		System.out.println(".........");
		System.out.println(".........");
		System.out.println(".........");

	}

}
