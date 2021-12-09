package org.vorpal.blade.services.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.transfer.BlindTransfer;
import org.vorpal.blade.framework.transfer.TransferListener;

/**
 * This class implements an example B2BUA with transfer capabilities.
 * 
 * @author Jeff McDonald
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class TransferServlet extends B2buaServlet implements TransferListener {
	public static SettingsManager<TransferSettings> settingsManager;

//	@SipApplicationKey
//	public static String sessionKey(SipServletRequest request) {
//
//	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		settingsManager = new SettingsManager<>(event, TransferSettings.class);
		sipLogger.logConfiguration(settingsManager.getCurrent());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		// TODO Auto-generated method stub

	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;
		TransferSettings ts = settingsManager.getCurrent();

		if (request.getMethod().equals("REFER")) {
			if (request.getHeader(ts.getFeatureEnableHeader()) != null
					&& request.getHeader(ts.getFeatureEnableHeader()).contains(ts.getFeatureEnableValue())) {
				callflow = new BlindTransfer(this);
			}
		}

		if (callflow == null) {
			callflow = super.chooseCallflow(request);
		}

		return callflow;
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

	@Override
	public void transferInitiated(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void transferAbandoned(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

}
