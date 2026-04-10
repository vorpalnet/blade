package org.vorpal.blade.framework.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

public class BladeServlet<T> extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;
	
	private SettingsManager<T> settings;
	
	
	
	
	
	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub
		return null;
	}

}
