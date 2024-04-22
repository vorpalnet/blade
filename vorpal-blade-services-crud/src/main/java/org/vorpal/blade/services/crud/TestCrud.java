package org.vorpal.blade.services.crud;

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
import org.vorpal.blade.services.crud.config.CrudConfigurationSample;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
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

public class TestCrud {

	public static void main(String[] args) throws JsonProcessingException {
		// TODO Auto-generated method stub
		
		CrudConfigurationSample config = new CrudConfigurationSample();

		ObjectMapper mapper;
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

		System.out.println("printing schema...");
		SubclassesResolver resolver = new SubclassesResolverImpl()
				.withClassesToScan(Arrays.asList(config.getClass().getName()));
		JsonSchemaConfig jsc = JsonSchemaConfig.html5EnabledSchema().withSubclassesResolver(resolver);
		JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(mapper, jsc);
		JsonNode jsonSchema = jsonSchemaGenerator.generateJsonSchema(config.getClass());
		String indented = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonSchema);
		System.out.println(indented);
		
		
		System.out.println("printing JSON...");
	//	mapper.writerWithDefaultPrettyPrinter().writeValue(System.out, _config);
		
		String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
		System.out.println(json);


	}

}
