package org.vorpal.blade.services.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
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
//public class TransferServlet extends B2buaServlet {
	public static TransferSettingsManager settingsManager;

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
			
			request.getApplicationSession().setAttribute("INITIAL_REFER", request);
			
			
			
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

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub
		
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
