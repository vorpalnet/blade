package org.vorpal.blade.test.uas.callflows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;

public class UasCallflow extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		// this code is temporary, need to build a sophisticated sequenced approach

		String strStatus = request.getRequestURI().getParameter("status");
		int status = Integer.parseInt(strStatus);
		sendResponse(request.createResponse(status));

	}

}
