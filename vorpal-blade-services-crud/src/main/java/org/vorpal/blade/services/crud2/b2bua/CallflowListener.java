package org.vorpal.blade.services.crud2.b2bua;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

public interface CallflowListener {

//	public SipServletRequest createRequest(SipServletRequest request);
//
//	public URI initialRequestURI(SipServletRequest request) throws ServletException, IOException;

	public SipServletRequest zzCreateInitialRequest(SipServletRequest origin) throws IOException, ServletParseException;

}
