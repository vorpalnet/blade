package org.vorpal.blade.services.proxy.registrar;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class SupervisedCallflow extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		sipLogger.finer(request, "SupervisedCallflow... No action required.");
	}

}
