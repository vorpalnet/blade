package org.vorpal.blade.test.uas.callflows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;

public class UasCallflow extends Callflow {

	private Integer status;

	public UasCallflow(Integer status) {
		this.status = status;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		sendResponse(request.createResponse(status));
	}

}
