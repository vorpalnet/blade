package org.vorpal.blade.test.config;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.InetAddressKeyDeserializer;
import org.vorpal.blade.framework.config.JsonAddressDeserializer;
import org.vorpal.blade.framework.config.JsonAddressSerializer;
import org.vorpal.blade.framework.config.JsonIPAddressDeserializer;
import org.vorpal.blade.framework.config.JsonIPAddressSerializer;
import org.vorpal.blade.framework.config.JsonUriDeserializer;
import org.vorpal.blade.framework.config.JsonUriSerializer;
import org.vorpal.blade.framework.proxy.ProxyPlan;
import org.vorpal.blade.framework.proxy.ProxyTier;
import org.vorpal.blade.framework.proxy.ProxyTier.Mode;
import org.vorpal.blade.services.proxy.balancer.ProxyBalancerConfig;

import com.bea.wcp.sip.engine.server.SipFactoryImpl;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import inet.ipaddr.IPAddress;

public class ProxyBalancerConfig1 extends ProxyBalancerConfig {

	public ProxyBalancerConfig1() throws ServletParseException {

		SipFactoryImpl sipFactory = new SipFactoryImpl(null, null);

		ProxyPlan plan1 = new ProxyPlan();

		ProxyTier proxyTier1 = new ProxyTier();
		proxyTier1.setMode(Mode.parallel);
		proxyTier1.setTimeout(15);

		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:transferor@blade1;status=501;delay=2"));

		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:transferor@blade2;status=502;delay=4"));

		plan1.getTiers().add(proxyTier1);

		ProxyTier proxyTier2 = new ProxyTier();
		proxyTier2.setMode(Mode.serial);
		proxyTier2.setTimeout(15);
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:transferor@blade3;status=503;delay=2"));
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:transferor@blade4;status=504;delay=2"));
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:transferor@blade5"));
		plan1.getTiers().add(proxyTier2);

		this.getPlans().put("test1", plan1);

	}

	public static void main(String[] args) throws JsonProcessingException, ServletParseException {
		ProxyBalancerConfig configuration = new ProxyBalancerConfig1();
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
