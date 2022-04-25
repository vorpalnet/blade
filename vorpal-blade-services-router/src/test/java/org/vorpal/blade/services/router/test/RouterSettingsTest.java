package org.vorpal.blade.services.router.test;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.junit.Before;
import org.junit.Test;
import org.vorpal.blade.framework.config.InetAddressKeyDeserializer;
import org.vorpal.blade.framework.config.JsonAddressDeserializer;
import org.vorpal.blade.framework.config.JsonAddressSerializer;
import org.vorpal.blade.framework.config.JsonIPAddressDeserializer;
import org.vorpal.blade.framework.config.JsonIPAddressSerializer;
import org.vorpal.blade.framework.config.JsonUriDeserializer;
import org.vorpal.blade.framework.config.JsonUriSerializer;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.services.router.config.RouterSettings;

import com.bea.wcp.sip.engine.server.SipFactoryImpl;
import com.bea.wcp.sip.engine.server.SipServletRequestImpl;
import com.bea.wcp.sip.engine.server.URIImpl;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import inet.ipaddr.IPAddress;

public class RouterSettingsTest {
	private RouterSettings settings;
	private SipServletRequest outboundRequest;

	@Before
	public void setup() {
		settings = new RouterSettings();
		outboundRequest = new SipServletRequestImpl();
		SettingsManager.setSipFactory(new SipFactoryImpl(null, null));
	}

	@Test
	public void test() {

		try {
			String output;
			ObjectMapper mapper = new ObjectMapper();

			mapper.registerModule(new SimpleModule().addDeserializer(URI.class, new JsonUriDeserializer()));
			mapper.registerModule(
					new SimpleModule().addDeserializer(javax.servlet.sip.Address.class, new JsonAddressDeserializer()));
			mapper.registerModule(new SimpleModule().addDeserializer(IPAddress.class, new JsonIPAddressDeserializer()));
			mapper.registerModule(new SimpleModule().addSerializer(URI.class, new JsonUriSerializer()));
			mapper.registerModule(
					new SimpleModule().addSerializer(javax.servlet.sip.Address.class, new JsonAddressSerializer()));
			mapper.registerModule(new SimpleModule().addSerializer(IPAddress.class, new JsonIPAddressSerializer()));

			mapper.registerModule(
					new SimpleModule().addKeyDeserializer(inet.ipaddr.Address.class, new InetAddressKeyDeserializer()));

			mapper.setSerializationInclusion(Include.NON_NULL);
			mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
			output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings);
			System.out.println(output);

			RouterSettings config2 = mapper.readValue(output, RouterSettings.class);
			output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config2);
			System.out.println(output);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Test
	public void test2() {

		try {
			outboundRequest.setRequestURI(new URIImpl("sip:192.168.1.1"));
			outboundRequest.setHeader("To", "sip:19974001234@192.168.1.1");
			settings.applyRoutes(outboundRequest);
			System.out.println("new uri: " + outboundRequest.getRequestURI().toString());

		} catch (Exception e) {
			e.printStackTrace();
		}

	}
	
	
	@Test
	public void test3() {

		try {
			outboundRequest.setRequestURI(new URIImpl("sip:10.173.101.120"));
			outboundRequest.setHeader("To", "sip:19974001234@10.173.101.120");
			settings.applyRoutes(outboundRequest);
			System.out.println("new uri: " + outboundRequest.getRequestURI().toString());

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
		RouterSettingsTest test = new RouterSettingsTest();
		test.setup();
		test.test3();

	}

}
