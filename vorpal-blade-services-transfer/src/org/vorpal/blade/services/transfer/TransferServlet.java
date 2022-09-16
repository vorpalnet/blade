package org.vorpal.blade.services.transfer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.transfer.AssistedTransfer;
import org.vorpal.blade.framework.transfer.BlindTransfer;
import org.vorpal.blade.framework.transfer.MediaTransfer;
import org.vorpal.blade.framework.transfer.TransferCondition;
import org.vorpal.blade.framework.transfer.TransferInitialInvite;
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
	public static TransferSettingsManager settingsManager;

//	@SipApplicationKey
//	public static String sessionKey(SipServletRequest request) {
//
//	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		settingsManager = new TransferSettingsManager(event, TransferSettings.class, new TransferSettingsSample());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		// TODO Auto-generated method stub
	}

	private Callflow chooseCallflowStyle(TransferSettings.TransferStyle transferStyle) {
		Callflow callflow = null;

		switch (transferStyle) {
		case media:
			callflow = new MediaTransfer(this);
			break;
		case assisted:
			callflow = new AssistedTransfer(this);
			break;
		case blind:
		default:
			callflow = new BlindTransfer(this);
		}

		return callflow;
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;
		TransferSettings ts = settingsManager.getCurrent();

		if (request.getMethod().equals("INVITE") && request.isInitial()) {
			callflow = new TransferInitialInvite();
		} else if (request.getMethod().equals("REFER")) {
			if (ts.getTransferAllRequests() == true) {
				sipLogger.finer(request, "Transferring all requests...");
				callflow = this.chooseCallflowStyle(ts.getDefaultTransferStyle());
			} else {
				for (TransferCondition tc : ts.getTransferConditions()) {
					
					
					
					if (true == tc.getCondition().checkAll(request)) {
						
						
						
						if (null != tc.getStyle()) {
							callflow = this.chooseCallflowStyle(tc.getStyle());
						} else {
							callflow = this.chooseCallflowStyle(ts.getDefaultTransferStyle());
						}
						break;
					}
				}
			}
		}

		if (callflow == null) {
			callflow = super.chooseCallflow(request);
		}

		return callflow;
	}

	@Override
	public void callStarted(SipServletRequest request) throws ServletException, IOException {

		ArrayList<String> headers = TransferServlet.settingsManager.getCurrent().getPreserveInviteHeaders();
		if (headers != null && headers.size() > 0) {
			HashMap<String, String> headersMap = new HashMap<>();
			Iterator<String> itr = headers.iterator();
			String headerName, headerValue;
			while (itr.hasNext()) {
				headerName = itr.next();
				headerValue = request.getHeader(headerName);
				if (headerValue != null) {
					headersMap.put(headerName, headerValue);
				}
			}

			if (headersMap.size() > 0) {
				request.getApplicationSession().setAttribute("PRESERVE_HEADERS", headersMap);
			}

		}

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
		SipApplicationSession appSession = request.getApplicationSession();
		HashMap<String, String> headersMap = (HashMap<String, String>) appSession.getAttribute("PRESERVE_HEADERS");
		if (headersMap != null) {
			for (Entry<String, String> entry : headersMap.entrySet()) {
				request.setHeader(entry.getKey(), entry.getValue());
			}
		}
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
