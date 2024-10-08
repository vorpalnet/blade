package org.vorpal.blade.framework.callflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

/**
 * The only purpose of this class is to implement the 'process' method so that
 * future classes can extend from it without having to implement a useless
 * method. It just makes the code look cleaner.
 */
public class ClientCallflow extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// do nothing;
	}

}
