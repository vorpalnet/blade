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

package org.vorpal.blade.framework.config;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.ListIterator;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletMessage.HeaderForm;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import com.bea.wcp.sip.engine.server.header.HeaderUtils;

public class SipUtil {

	public static String getAccountName(Address address) {
		return getAccountName(address.getURI());
	}

	public static String getAccountName(URI _uri) {
		SipURI sipUri = (SipURI) _uri;
		return sipUri.getUser().toLowerCase() + "@" + sipUri.getHost().toLowerCase();
	}

	public static void copyHeadersAndContent(SipServletMessage origin, SipServletMessage destination) throws UnsupportedEncodingException, IOException {

		String headerName, headerValue;
		Iterator<String> itr = origin.getHeaderNames();
		while (itr.hasNext()) {
			headerName = itr.next();
			if (false == HeaderUtils.isSystemHeader(headerName, true) && false == headerName.equals("Allow") && false == headerName.equals("Call-Info")) {
				destination.setHeaderForm(HeaderForm.LONG);
				// destination.setHeaderForm(origin.getHeaderForm());
				ListIterator<String> headers = origin.getHeaders(headerName);

				while (headers.hasNext()) {
					headerValue = headers.next();

					if (headerName.equals("Allow-Events") && headerValue.equals("kpml")) {
						// do nothing;
					} else {
						if (HeaderUtils.isUnique(headerName)) {
							destination.setHeader(headerName, headerValue);
						} else {
							destination.addHeader(headerName, headerValue);
						}
					}
				}
			}
		}

		destination.setContent(origin.getContent(), origin.getContentType());

	}

}
