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
import org.vorpal.blade.framework.deprecated.proxy.ProxyRule;
import org.vorpal.blade.framework.proxy.ProxyTier;

import com.bea.core.repackaged.springframework.aop.framework.ProxyConfig;
import com.bea.wcp.sip.engine.server.SipFactoryImpl;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import inet.ipaddr.IPAddress;

public class ProxySample extends ProxyConfig {

//--400 Bad Request 
//--401 Unauthorized
//--402 Payment Required
//--403 Forbidden
//--404 Not Found
//--405 Method Not Allowed
//--406 Not Acceptable
//--407 Proxy Authentication Required
//--408 Request Timeout
//--409 Conflict
//--410 Gone
//--411 Length Required
//--412 Conditional Request Failed
//--413 Request Entity Too Large
//--414 Request-URI Too Long
//--415 Unsupported Media Type
//--416 Unsupported URI Scheme
//--417 Unknown Resource-Priority
//--420 Bad Extension
//--421 Extension Required
//--422 Session Interval Too Small
//--423 Interval Too Brief
//--424 Bad Location Information
//--425 Bad Alert Message
//--428 Use Identity Header
//--429 Provide Referrer Identity
//--430 Flow Failed
//--433 Anonymity Disallowed
//--436 Bad Identity-Info
//--437 Unsupported Certificate
//--438 Invalid Identity Header
//--439 First Hop Lacks Outbound Support
//--440 Max-Breadth Exceeded
//--469 Bad Info Package
//--470 Consent Needed
//--480 Temporarily Unavailable
//--481 Call/Transaction Does Not Exist
//--482 Loop Detected
//--483 Too Many Hops
//--484 Address Incomplete
//--485 Ambiguous
//--486 Busy Here
//--487 Request Terminated
//--488 Not Acceptable Here
//--489 Bad Event
//--491 Request Pending
//--493 Undecipherable
//--494 Security Agreement Required

	public ProxySample() {

		try {

//			SipFactoryImpl sipFactory = new SipFactoryImpl(null, null);
//
//			this.setStateless(true);
//
//			ProxyRule rule1 = new ProxyRule();
//			rule1.setId("transferor");
//			this.getRules().put("transferor", rule1);
//			
//			ProxyTier proxyTier1 = new ProxyTier();
//			proxyTier1.setMode(ProxyTier.Mode.parallel);
//			proxyTier1.setTimeout(15);
//			proxyTier1.getEndpoints().add(sipFactory.createURI("sip:transferor@host1.net;status=401"));
//			proxyTier1.getEndpoints().add(sipFactory.createURI("sip:transferor@host2.net;status=402"));
//			rule1.getTiers().add(proxyTier1);
//
//			ProxyTier proxyTier2 = new ProxyTier();
//			proxyTier2.setMode(ProxyTier.Mode.serial);
//			proxyTier1.setTimeout(15);
//			proxyTier2.getEndpoints().add(sipFactory.createURI("sip:transferor@host3.net;status=403"));
//			proxyTier2.getEndpoints().add(sipFactory.createURI("sip:transferor@host4.net;statis=404"));
//			rule1.getTiers().add(proxyTier2);

			
			
			
//		ProxyRule rule2 = new ProxyRule();
//		rule2.setId("transferor");
//		this.getRules().put("transferor", rule2);
//		
//		ProxyRule rule3 = new ProxyRule();
//		rule3.setId("target");
//		this.getRules().put("target", rule3);
//
//		ProxyRule rule4 = new ProxyRule();
//		rule4.setId("transferee");
//		this.getRules().put("transferee", rule4);



//		ProxyTier proxyTier2 = new ProxyTier();
//		rule2.getTiers().add(proxyTier2);
//		proxyTier2.setMode(ProxyTier.Mode.parallel);
//		proxyTier2.setTimeout(30);
//		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:transferor@vorpal.net;status=404"));

//		ProxyTier proxyTier3 = new ProxyTier();
//		rule3.getTiers().add(proxyTier3);
//		proxyTier3.setMode(ProxyTier.Mode.parallel);
//		proxyTier3.setTimeout(30);
//		proxyTier3.getEndpoints().add(sipFactory.createURI("sip:target@vorpal.net"));

//		ProxyTier proxyTier4 = new ProxyTier();
//		rule4.getTiers().add(proxyTier4);
//		proxyTier4.setMode(ProxyTier.Mode.parallel);
//		proxyTier4.setTimeout(30);
//		proxyTier4.getEndpoints().add(sipFactory.createURI("sip:transferee@vorpal.net"));

//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=400");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=401");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=402");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=403");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=404");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=405");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=406");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=407");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=408");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=409");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=410");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=411");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=412");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=413");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=414");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=415");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=416");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=417");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=420");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=421");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=423");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=424");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=425");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=428");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=429");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=430");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=433");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=436");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=437");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=438");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=439");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=440");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=469");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=470");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=480");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=481");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=482");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=483");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=484");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=485");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=486");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=487");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=488");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=489");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=491");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=493");
//		proxyTier1.getEndpoints().add("sip:bob@vorpal.net;status=494");

//		ProxyTier proxyTier2 = new ProxyTier();
//		rule1.getTiers().add(proxyTier2);
//		proxyTier2.setMode(ProxyTier.Mode.parallel);
//		proxyTier2.setTimeout(30);
//		proxyTier2.getEndpoints().add("sip:bob@vorpal.net;id=4;delay=5;status=404");
//		proxyTier2.getEndpoints().add("sip:bob@vorpal.net;id=5;delay=5;status=404");
//		proxyTier2.getEndpoints().add("sip:bob@vorpal.net;id=6;delay=5;status=200");

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void main(String[] args) throws JsonProcessingException {

		ProxySample configuration = new ProxySample();

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
