package org.vorpal.blade.test.uac;

import java.io.IOException;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class UserAgentClientServlet extends B2buaServlet {

	public static SettingsManager<UserAgentClientConfig> settings;

	private static final long serialVersionUID = 1L;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settings = new SettingsManager<>(event, UserAgentClientConfig.class, new UserAgentClientConfigSample());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		settings.unregister();
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		outboundRequest.setAttribute("noKeepAlive", Boolean.TRUE); // for testing keep alive
		
		for (Entry<String, String> entry : settings.getCurrent().headers.entrySet()) {
			outboundRequest.setHeader(entry.getKey(), entry.getValue());
		}
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

}
