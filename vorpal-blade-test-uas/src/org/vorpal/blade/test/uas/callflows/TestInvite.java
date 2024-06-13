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

import org.vorpal.blade.framework.b2bua.B2buaListener;
import org.vorpal.blade.framework.b2bua.InitialInvite;

public class TestInvite extends InitialInvite {
	static final long serialVersionUID = 1L;
	private B2buaListener b2buaListener = null;
//	int delay = 0;
//	int status = 0;
//	int duration = 0;
//	String durationTimerId = null;
//	SipServletResponse delayedResponse = null;

	public TestInvite() {
	}

	public TestInvite(B2buaListener b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	public static int inSeconds(String strDuration) {
		int duration = 0;

		try {
			if (strDuration != null) {
				if (strDuration.contains("s")) { // seconds
					duration = Integer.parseInt(strDuration.replace("s", ""));
				} else if (strDuration.contains("m")) { // minutes
					duration = Integer.parseInt(strDuration.replace("m", "")) * 60;
				} else if (strDuration.contains("h")) { // hours
					duration = Integer.parseInt(strDuration.replace("h", "")) * 60 * 60;
				} else if (strDuration.contains("d")) { // days
					duration = Integer.parseInt(strDuration.replace("d", "")) * 60 * 60 * 24;
				} else if (strDuration.contains("y")) { // years
					duration = Integer.parseInt(strDuration.replace("y", "")) * 60 * 60 * 24 * 365;
				} else {
					duration = Integer.parseInt(strDuration); // seconds
				}
			}
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

		return duration;
	}

	public static int inMilliseconds(String strDuration) {

		return inSeconds(strDuration) * 1000;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		sipLogger.finer(request, "recieved this: \n"+request.toString());
		
		
		SipServletResponse response = request.createResponse(200);
		response.setContent(qfinitiResponse.getBytes(), "application/sdp");

		
		sipLogger.finer(request, "sending this: \n"+request.toString());

		sendResponse(response);

	}

//		@Override
	public void processXX(SipServletRequest request) throws ServletException, IOException {
		try {

			String strStatus = request.getRequestURI().getParameter("status");
			int status = (strStatus != null) ? Integer.parseInt(strStatus) : 200;

			String strDelay = request.getRequestURI().getParameter("delay");
			int delay = (strDelay != null) ? inSeconds(strDelay) : 0;

			if (delay > 0) {

				scheduleTimer(request.getApplicationSession(), delay, (timer) -> {

//					sipLogger.severe("1. Is request null? " + (request == null));
//					sipLogger.severe("1. Is request committed? " + request.isCommitted());
//					sipLogger.severe("1. Is request session null? " + (request.getSession() == null));
//					if (request.getSession() != null) {
//						sipLogger.severe("1. Is request session ready to invalidate? "
//								+ (request.getSession().isReadyToInvalidate()));
//						sipLogger.severe("1. Is request session valid? " + (request.getSession().isValid()));
//					}

					if (request.isCommitted() == false) {
						sendResponse(request.createResponse(status));
					}

				});

			} else {

//				sipLogger.severe("2. Is request null? " + (request == null));
//				sipLogger.severe("2. Is request committed? " + request.isCommitted());
//				sipLogger.severe("2. Is request session null? " + (request.getSession() == null));
//				if (request.getSession() != null) {
//					sipLogger.severe("2. Is request session ready to invalidate? "
//							+ (request.getSession().isReadyToInvalidate()));
//					sipLogger.severe("2. Is request session valid? " + (request.getSession().isValid()));
//				}

				sendResponse(request.createResponse(status));
			}

			if (status >= 200 && status < 300) {
				String strDuration = request.getRequestURI().getParameter("duration");
				int duration = (strDuration != null) ? inSeconds(strDuration) : 30;

				String timerId = scheduleTimer(request.getApplicationSession(), duration, (timer) -> {

//					sipLogger.severe("3. Is request null? " + (request == null));
//					sipLogger.severe("3. Is request committed? " + request.isCommitted());
//					sipLogger.severe("3. Is request session null? " + (request.getSession() == null));
//					if (request.getSession() != null) {
//						sipLogger.severe("3. Is request session ready to invalidate? "
//								+ (request.getSession().isReadyToInvalidate()));
//						sipLogger.severe("3. Is request session valid? " + (request.getSession().isValid()));
//					}

					if (request.getSession() != null) {
						sendRequest(request.getSession().createRequest("BYE"));
					}

				});

			}

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

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

	public final static String qfinitiResponse = "" + //
			"v=0\r\n" + //
			"o=Qfiniti 4058038202 4058038202 IN IP4 10.204.66.204\r\n" + //
			"s=Qfiniti SIPREC Session\r\n" + //
			"c=IN IP4 10.204.66.204\r\n" + //
			"t=4058038202 0\r\n" + //
			"m=audio 40188 RTP/AVP 101 0 8 18\r\n" + //
			"a=rtpmap:0 PCMU/8000\r\n" + //
			"a=rtpmap:8 PCMA/8000\r\n" + //
			"a=rtpmap:18 G729/8000\r\n" + //
			"a=rtpmap:101 telephone-event/8000\r\n" + //
			"a=ptime:20\r\n" + //
			"a=label\r\n" + //
			"m=audio 40190 RTP/AVP 101 0 8 18\r\n" + //
			"a=rtpmap:0 PCMU/8000\r\n" + //
			"a=rtpmap:8 PCMA/8000\r\n" + //
			"a=rtpmap:18 G729/8000\r\n" + //
			"a=rtpmap:101 telephone-event/8000\r\n" + //
			"a=ptime:20\r\n" + //
			"a=label\r\n";

}
