package org.vorpal.blade.framework.tpcc;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.callflow.Callflow;

/**
 * This is a simple Third-Party Call-Control callflow.
 * 
 * <pre>{@code
 * 
 *   Alice               BLADE                Bob
 * ----------           --------             -----
 *     |                   |                   |
 *     |            INVITE |                   |
 *     |<------------------|                   |
 *     | 200 OK            |
 *     |------------------>|                   |
 *     |                   |                   |
 *       
 * }</pre>
 */
public class TestReinvite extends Callflow {

	private static final long serialVersionUID = 3456324552689435347L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(200);
		sendResponse(response, (ack) -> {
			// do nothing;
		});
	}

}
