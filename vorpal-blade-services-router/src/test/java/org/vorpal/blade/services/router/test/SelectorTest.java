package org.vorpal.blade.services.router.test;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.junit.Before;
import org.junit.Test;
import org.vorpal.blade.services.router.config.Selector;

import com.bea.wcp.sip.engine.server.SipServletRequestImpl;
import com.bea.wcp.sip.engine.server.URIImpl;

public class SelectorTest {

	private Selector s1;
	private SipServletRequest request;

	@Before
	public void setup() {
		s1 = new Selector();
		s1.setAttribute("Request-URI");
		s1.setId("s1");
		s1.setPattern("^(sips?):([^@]+)(?:@(.+))?$");
		s1.setExpression("$3");

		request = new SipServletRequestImpl();

	}

	@Test
	public void test1() {
		URI uri = new URIImpl("sip:bob@vorpal.org:5060;loc=wonderland");
		request.setRequestURI(uri);
//		String key = s1.findKey(request);

//		System.out.println("key: " + key);
	}
	
	


}
