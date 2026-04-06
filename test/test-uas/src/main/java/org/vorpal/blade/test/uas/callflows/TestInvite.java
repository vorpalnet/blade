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
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.InitialInvite;
import org.vorpal.blade.test.uas.UasServlet;
import org.vorpal.blade.test.uas.config.TestUasConfig;

/// Callflow for handling initial INVITE requests.
///
/// Response behavior is controlled by (in order of precedence):
/// 1. Error map — phone number match overrides status
/// 2. Request URI parameters — `?status=503&delay=5s&duration=60s`
/// 3. Config defaults — set via REST API or configuration file
public class TestInvite extends InitialInvite {
	static final long serialVersionUID = 1L;
	private B2buaListener b2buaListener = null;

	public TestInvite() {
	}

	public TestInvite(B2buaListener b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	/// Parses a human-readable duration string to seconds.
	/// Supports suffixes: s (seconds), m (minutes), h (hours), d (days), y (years).
	/// Plain numbers are treated as seconds.
	public static int inSeconds(String strDuration) {
		int duration = 0;
		try {
			if (strDuration != null) {
				switch (strDuration.charAt(strDuration.length() - 1)) {
				case 's':
					duration = Integer.parseInt(strDuration.replace("s", ""));
					break;
				case 'm':
					duration = Integer.parseInt(strDuration.replace("m", "")) * 60;
					break;
				case 'h':
					duration = Integer.parseInt(strDuration.replace("h", "")) * 60 * 60;
					break;
				case 'd':
					duration = Integer.parseInt(strDuration.replace("d", "")) * 60 * 60 * 24;
					break;
				case 'y':
					duration = Integer.parseInt(strDuration.replace("y", "")) * 60 * 60 * 24 * 365;
					break;
				default:
					duration = Integer.parseInt(strDuration);
					break;
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
		try {
			TestUasConfig config = UasServlet.settingsManager.getCurrent();

			// Parse URI parameters (override config defaults)
			String strStatus = request.getRequestURI().getParameter("status");
			int status = (strStatus != null) ? Integer.parseInt(strStatus) : config.getDefaultStatus();

			String strDelay = request.getRequestURI().getParameter("delay");
			int delay = (strDelay != null) ? inSeconds(strDelay) : config.getDefaultDelaySeconds();

			String strDuration = request.getRequestURI().getParameter("duration");
			int duration = (strDuration != null) ? inSeconds(strDuration) : config.getDefaultDurationSeconds();

			// Error map overrides status
			if (request.getTo().getURI() instanceof SipURI) {
				String toUser = ((SipURI) request.getTo().getURI()).getUser();
				Integer errorStatus = config.getErrorMap().get(toUser);
				if (errorStatus != null) {
					status = errorStatus;
				}
			}

			// Resolve SDP content
			String sdp = (config.getSdpContent() != null) ? config.getSdpContent() : qfinitiResponse;

			final int finalStatus = status;
			final String finalSdp = sdp;

			// Send response (with optional delay)
			if (delay > 0) {
				scheduleTimer(request.getApplicationSession(), delay, (timer) -> {
					if (!request.isCommitted()) {
						SipServletResponse response = request.createResponse(finalStatus);
						if (finalStatus >= 200 && finalStatus < 300) {
							response.setContent(finalSdp.getBytes(), "application/sdp");
						}
						sendResponse(response);
					}
				});
			} else {
				SipServletResponse response = request.createResponse(status);
				if (status >= 200 && status < 300) {
					response.setContent(sdp.getBytes(), "application/sdp");
				}
				sendResponse(response);
			}

			// Schedule auto-BYE for successful responses
			if (status >= 200 && status < 300 && duration > 0) {
				scheduleTimer(request.getApplicationSession(), duration, (timer) -> {
					if (request.getSession() != null && request.getSession().isValid()) {
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
