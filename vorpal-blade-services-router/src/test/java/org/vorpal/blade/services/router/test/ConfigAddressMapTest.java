package org.vorpal.blade.services.router.test;

import javax.servlet.sip.SipServletRequest;

import org.junit.Before;
import org.junit.Test;
import org.vorpal.blade.services.router.config.ConfigAddressMap;
import org.vorpal.blade.services.router.config.Selector;
import org.vorpal.blade.services.router.config.Translation;

import com.bea.wcp.sip.engine.server.SipServletRequestImpl;

import inet.ipaddr.IPAddressString;

public class ConfigAddressMapTest {

	private SipServletRequest request;
	private ConfigAddressMap addressMap;

	@Before
	public void setup() {
		request = new SipServletRequestImpl();
		addressMap = new ConfigAddressMap();

		addressMap.selector = new Selector("remote-ip", "Remote-IP", "^(.*)$", "$1");

		Translation t1 = new Translation();
		t1.id = "127-0-0-1";
		t1.requestUri = "sip:localhost";
		addressMap.map.put(new IPAddressString("127.0.0.1").getAddress(), t1);

	}

	@Test
	public void test() {

		// request.getRemoteAddr();

		Translation t;
		t = addressMap.lookup(request);

		System.out.println("Translation: " + t);
		System.out.println("Translation.id: " + t.id);
		System.out.println("Translation.description: " + t.description);
		System.out.println("Translation.requestUri: " + t.requestUri);

	}

	public static void main(String[] args) {
		ConfigAddressMapTest test = new ConfigAddressMapTest();
		test.setup();
		test.test();

	}

}
