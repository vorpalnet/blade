package org.vorpal.blade.test.uas;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.B2buaListener;
import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.test.uas.callflows.TestInvite;
import org.vorpal.blade.test.uas.callflows.TestRefer;
import org.vorpal.blade.test.uas.config.TestUasConfig;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class UasServlet extends B2buaServlet implements B2buaListener {

	private static final long serialVersionUID = 1L;
	public static SettingsManager<TestUasConfig> settingsManager;

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		if (request.isInitial()) {

			if (request.getRequestURI().getParameter("refer") != null) {
				callflow = new TestRefer();
			} else {
				callflow = new TestInvite();
			}

		} else {
			callflow = super.chooseCallflow(request);
		}

		return callflow;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		settingsManager = new SettingsManager<TestUasConfig>(event, TestUasConfig.class);
		sipLogger.logConfiguration(settingsManager.getCurrent());

	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		// do nothing;
	}

	@Override
	public void callStarted(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callConnected(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callDeclined(SipServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callAbandoned(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

}
