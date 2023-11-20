package org.vorpal.blade.services.queue.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.InitialInvite;
import org.vorpal.blade.services.queue.CallflowQueue;

/**
 * 
 */
public class Continue extends InitialInvite {
	private static final long serialVersionUID = 1L;

	private CallflowQueue queue;
	private long startTime;
	private SipServletRequest aliceRequest;

	public Continue(CallflowQueue queue, SipServletRequest request, long startTime) {
		super(null);
		
		this.queue = queue;
		this.aliceRequest = request;
		this.startTime = startTime;
		
	}

	@Override
	public void process(SipServletRequest willBeNull) throws ServletException, IOException {
		
	
	}

}
