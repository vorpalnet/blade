package org.vorpal.blade.services.router.test;

import javax.servlet.sip.SipServletRequest;

import org.junit.Before;
import org.junit.Test;
import org.vorpal.blade.services.router.config.ConfigPrefixMap;
import org.vorpal.blade.services.router.config.Selector;
import org.vorpal.blade.services.router.config.Translation;

import com.bea.wcp.sip.engine.server.SipServletRequestImpl;

public class ConfigPrefixMapTest {

	SipServletRequest request;
	ConfigPrefixMap prefixMap;

	@Before
	public void setUp() {
		request = new SipServletRequestImpl();
		request.setHeader("To", "sip:18165551234@vorpal.org");
		request.setHeader("From", "sip:19135556789@vorpal.org");

		prefixMap = new ConfigPrefixMap();
		prefixMap.selector = new Selector();
		prefixMap.selector.setAttribute("To");
		prefixMap.selector.setPattern("^(sips?):([^@]+)(?:@(.+))?$");
		prefixMap.selector.setExpression("$2");
		prefixMap.selector.setId("s1");

		Translation t1 = new Translation();
		t1.id = "t4";
		t1.requestUri = "sip:cc";
		prefixMap.map.put("1", t1);

		Translation t2 = new Translation();
		t2.id = "t2";
		t2.requestUri = "sip:cc-npa";
		prefixMap.map.put("1816", t2);

		Translation t3 = new Translation();
		t3.id = "t3";
		t3.requestUri = "sip:cc-npa-nxx";
		prefixMap.map.put("1816555", t3);

		Translation t4 = new Translation();
		t4.id = "t4";
		t4.requestUri = "sip:cc-npa-nxx-xxxx";
		prefixMap.map.put("18165551234", t4);
	}

	@Test
	public void test() {

		request.setHeader("To", "sip:99999999999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request));

		request.setHeader("To", "sip:19999999999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18999999999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18199999999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18169999999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18165999999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18165599999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18165559999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18165551999@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18165551299@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18165551239@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

		request.setHeader("To", "sip:18165551234@vorpal.org");
		System.out.println(request.getHeader("To") + " : " + prefixMap.lookup(request).requestUri);

	}

	public static void main(String[] args) {
		ConfigPrefixMapTest test = new ConfigPrefixMapTest();
		test.setUp();
		test.test();

	}

}
