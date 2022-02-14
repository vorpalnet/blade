package org.vorpal.blade.services.keepalive;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.callflow.Callflow;

public abstract class KeepAliveCallflow extends Callflow implements javax.servlet.sip.SessionKeepAlive.Callback{

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		
	}

	@Override
	abstract public void handle(SipSession sipSession);
	
}
