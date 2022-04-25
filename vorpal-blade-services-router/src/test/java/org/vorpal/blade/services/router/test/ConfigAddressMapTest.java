package org.vorpal.blade.services.router.test;

import javax.servlet.sip.SipServletRequest;

import org.junit.Test;
import org.vorpal.blade.services.router.config.ConfigAddressMap;
import org.vorpal.blade.services.router.config.Translation;

import com.bea.wcp.sip.engine.server.SipServletRequestImpl;

import inet.ipaddr.IPAddressString;

public class ConfigAddressMapTest {

	private SipServletRequest request;
	private ConfigAddressMap addressMap;

	@Test
	public void setup() {
		request = new SipServletRequestImpl();

		addressMap = new ConfigAddressMap();
		
		Translation t1 = new Translation();
		t1.id = "192.168.1.1";
		t1.requestUri = "sip:192.168.1.1";
		
		
		addressMap.map.put(new IPAddressString("192.168.1.1").getAddress(), t1);
		
	}

	@Test
	public void test() {
		
		request.getRemoteAddr();
		
		Translation t;
		t = addressMap.lookup(request);
		
	}

}
