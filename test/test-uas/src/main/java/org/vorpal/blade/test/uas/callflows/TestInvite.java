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

import javax.mail.MessagingException;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/// Endpoint-mode responder for an initial INVITE. Behavior comes entirely
/// from the Request-URI:
///
/// - `status` — the SIP response code to send (default `200`). A 2xx answer
///   carries a blackhole/mute SDP (`c=0.0.0.0`, `a=inactive`) built from the
///   caller's offer by [Callflow#hold]; any other code is sent bare.
/// - `delay` — for an answered (2xx) call, how long to keep the call up
///   before sending `BYE`. `0`/absent means no auto-teardown. Accepts a bare
///   integer (milliseconds) or a value with an `ms`/`s`/`m`/`h` suffix, e.g.
///   `delay=5000`, `delay=5s`, `delay=500ms`, `delay=2m`.
public class TestInvite extends Callflow {
	static final long serialVersionUID = 1L;

	/// Parses a delay to milliseconds: a bare integer is milliseconds; the
	/// suffixes `ms`, `s`, `m`, `h` are honored. Returns `0` if unparseable.
	static long parseMillis(String value) {
		if (value == null) {
			return 0;
		}
		value = value.trim().toLowerCase();
		try {
			if (value.endsWith("ms")) {
				return Long.parseLong(value.substring(0, value.length() - 2).trim());
			} else if (value.endsWith("s")) {
				return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 1000L;
			} else if (value.endsWith("m")) {
				return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 60_000L;
			} else if (value.endsWith("h")) {
				return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 3_600_000L;
			}
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			sipLogger.warning("TestInvite: unparseable delay '" + value + "', treating as 0");
			return 0;
		}
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		URI ruri = request.getRequestURI();

		String strStatus = ruri.getParameter("status");
		int status = (strStatus != null) ? Integer.parseInt(strStatus) : 200;
		long delayMs = parseMillis(ruri.getParameter("delay"));

		boolean successful = status >= 200 && status < 300;

		SipServletResponse response = request.createResponse(status);
		if (successful) {
			try {
				hold(request, response); // blackhole/mute answer derived from the offer
			} catch (MessagingException e) {
				throw new IOException("TestInvite: failed to build hold answer from offer", e);
			}
		}
		sendResponse(response);

		// For an answered call, hold it up for `delay`, then tear it down.
		if (successful && delayMs > 0) {
			startTimer(request.getApplicationSession(), delayMs, false, (timer) -> {
				SipSession session = request.getSession();
				if (session != null && session.isValid()) {
					sendRequest(session.createRequest("BYE"));
				}
			});
		}
	}

}
