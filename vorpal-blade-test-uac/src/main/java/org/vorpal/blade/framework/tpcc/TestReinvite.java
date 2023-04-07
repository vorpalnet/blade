package org.vorpal.blade.framework.tpcc;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.test.client.Header;

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

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipServletResponse response = request.createResponse(200);
		sendResponse(response, (ack) -> {
			// do nothing;
		});
	}

}
