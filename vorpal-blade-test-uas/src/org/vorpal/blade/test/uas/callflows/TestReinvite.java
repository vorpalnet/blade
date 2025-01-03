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

package org.vorpal.blade.test.uas.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.InitialInvite;

public class TestReinvite extends InitialInvite {
	static final long serialVersionUID = 1L;

	public TestReinvite() {
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		SipServletResponse response = request.createResponse(200);
		response.setContent(blackhole, "application/sdp");
		sendResponse(response);

	}

	public final static String blackhole = "" + //
			"v=0\r\n" + //
			"o=CiscoSystemsCCM-SIP 3751 1 IN IP4 127.0.0.1\r\n" + //
			"s=SIP Call\r\n" + //
			"c=IN IP4 0.0.0.0\r\n" + //
			"b=TIAS:64000\r\n" + //
			"b=AS:64\r\n" + //
			"t=0 0\r\n" + //
			"m=audio 24580 RTP/AVP 0\r\n" + //
			"a=rtpmap:0 pcmu/8000\r\n" + //
			"a=ptime:20\r\n" + //
			"a=inactive\r\n";

}
