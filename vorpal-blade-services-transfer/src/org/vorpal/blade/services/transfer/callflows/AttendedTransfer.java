/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

/*
 *   Notes:
 *   https://www.dialogic.com/webhelp/BorderNet2020/2.2.0/WebHelp/sip_rfr_calltrans.htm
 * 
 * 
 *   |------------------->|------------------->|------------------->|
 *   |<-------------------|<-------------------|<-------------------|
 *   |                    |                    |                    |
 */

/* Transferor             BLADE              Transferee             Target
 *     |                    |                    |                    |
 *     | RTP                |                    |                    |
 *     |<=======================================>|                    |
 *     |                    |                    |                    |
 *     |                    |              REFER |                    |    Refer-To: <sip:carol@vorpal.net>
 *     |                    |<-------------------|                    |    Referred-By: <sip:bob@vorpal.net>
 *     |                    | 202 Accepted       |                    |
 *     |                    |------------------->|                    |
 *     |                    | NOTIFY             |                    |    Subscription-State: active
 *     |                    |------------------->|                    |    Event: refer
 *     |                    |             200 OK |                    |    Content-Type: message/sipfrag
 *     |                    |<-------------------|                    |    Contact: ???
 *     |                    |                    |                    |    Body = SIP/2.0 100 Trying
 *     |             INVITE |                    |                    |
 *     |<-------------------|                    |                    |
 *     | 200 OK             |                    |                    |
 *     |------------------->|                    |                    |
 *     |                    | INVITE             |                    |
 *     |                    |---------------------------------------->|
 *     |                    |                    |             200 OK |
 *     |                    |<----------------------------------------|
 *     |                    | NOTIFY             |                    |    Subscription-State: active
 *     |                    |------------------->|                    |    Event: refer
 *     |                    |             200 OK |                    |    Content-Type: message/sipfrag
 *     |                    |<-------------------|                    |    Contact: ???
 *     |                ACK |                    |                    |    Body = SIP/2.0 200 OK
 *     |<-------------------|                    |                    |
 *     |                    | ACK                |                    |
 *     |                    |---------------------------------------->|
 *     |                    |                    | RTP                |
 *     |                    |                    |<==================>|
 *     |                BYE |                    |                    |
 *     |<-------------------|                    |                    |
 *     | 200 OK             |                    |                    |
 *     |------------------->|                    |                    |
 *     |                    |                    |                    |
 */

package org.vorpal.blade.services.transfer.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callback;


// Notes: 
// https://www.dialogic.com/webhelp/csp1010/8.4.1_ipn3/sip_software_chap_-_sip_notify_subscription_state.htm


public class AttendedTransfer extends Transfer {
	static final long serialVersionUID = 1L;
	private SipServletRequest aliceRequest;
	private Callback<SipServletRequest> loopOnPrack;

	public AttendedTransfer(TransferListener referListener) {
		super(referListener);
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

	}

}
