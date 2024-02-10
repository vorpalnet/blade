package org.vorpal.blade.test.config;

import javax.servlet.sip.Address;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.InetAddressKeyDeserializer;
import org.vorpal.blade.framework.config.JsonAddressDeserializer;
import org.vorpal.blade.framework.config.JsonAddressSerializer;
import org.vorpal.blade.framework.config.JsonIPAddressDeserializer;
import org.vorpal.blade.framework.config.JsonIPAddressSerializer;
import org.vorpal.blade.framework.config.JsonUriDeserializer;
import org.vorpal.blade.framework.config.JsonUriSerializer;
import org.vorpal.blade.services.proxy.router.ProxyRouterConfigSample;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import inet.ipaddr.IPAddress;

public class ProxyRouterConfig1 {

	public static void main(String[] args) throws JsonProcessingException {

		ProxyRouterConfigSample configuration = new ProxyRouterConfigSample();

		
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
