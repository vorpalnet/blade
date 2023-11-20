package org.vorpal.blade.services.queue.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.services.queue.CallflowQueue;

/**
 * 
 */
public class Ringing extends Callflow {
	private static final long serialVersionUID = 1L;
	private String ringingTimer;
	private long startTime;
	private CallflowQueue queue;
	private Callflow continueCallflow;

	public Ringing(CallflowQueue queue) {
		this.queue = queue;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		startTime = System.currentTimeMillis();

		SipApplicationSession appSession = request.getApplicationSession();

		sendResponse(request.createResponse(180));
		
//		continueCallflow = new Continue(queue, startTime);
		
		
		

		ringingTimer = schedulePeriodicTimer(appSession, 0, (timer) -> {
			try {
				sendResponse(request.createResponse(180));
			} catch (Exception e) {
				long stopTime = System.currentTimeMillis();
				sipLogger.warning(request,
						"Unable to send 180 Ringing after " + SECONDS(stopTime - startTime) + " seconds.");
			}

		});

		this.expectRequest(appSession, CANCEL, (cancelRequest) -> {
			long stopTime = System.currentTimeMillis();
			sipLogger.info(request, "Call canceled after " + SECONDS(stopTime - startTime) + " seconds.");
			this.cancelTimer(appSession, ringingTimer);
			queue.getCallflows().remove(continueCallflow);
		});

	}

	private int SECONDS(long milliseconds) {
		return Math.round(milliseconds / 1000);
	}

}
