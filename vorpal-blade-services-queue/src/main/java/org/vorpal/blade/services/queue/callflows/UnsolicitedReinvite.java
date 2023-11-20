package org.vorpal.blade.services.queue.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;

/**
 * This class connects to a media server to play announcements while the caller
 * is placed in a queue
 * 
 * <pre>
 * {@code
 * 
 *   Alice               QUEUE               media                Bob
 *   -----               -----               -----                ---
 *     |                   |                   |                   |
 *     | INVITE            |                   |                   | ; B2buaCallflow
 *     |------------------>|                   |                   |
 *     |       180 Ringing |                   |                   |
 *     |<------------------|                   |                   |
 *     |                   | INVITE            |                   |
 *     |                   |------------------>|                   |
 *     |                   |            200 OK |                   |
 *     |                   |<------------------|                   |
 *     |            200 OK |                   |                   |
 *     |<------------------|                   |                   |
 *     | ACK               |                   |                   |
 *     |------------------>|                   |                   |
 *     |                   | ACK               |                   |
 *     |                   |------------------>|                   |
 *     |                |--|                   |                   |
 *     |          Queue |  |                   |                   | ; Queued handled by 'callStarted' in B2bua
 *     |                |->|                   |                   |
 *     |                   | INVITE            |                   | ; UnsolicitedReinvite (the code in this class)
 *     |                   |-------------------------------------->|
 *     |                   |                   |            200 OK |
 *     |                   |<--------------------------------------|
 *     |            INVITE |                   |                   |
 *     |<------------------|                   |                   |
 *     | ACK               |                   |                   |
 *     |------------------>|                   |                   |
 *     |                   | ACK               |                   |
 *     |                   |-------------------------------------->|
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |
 *     |                   |                   |                   |


 * }
 * </pre>
 */
public class UnsolicitedReinvite extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

}
