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
	
	
	public static void copyHeadersAndContent(SipServletMessage origin, SipServletMessage destination)
			throws UnsupportedEncodingException, IOException {

		String headerName, headerValue;
		Iterator<String> itr = origin.getHeaderNames();
		while (itr.hasNext()) {
			headerName = itr.next();
			if (false == HeaderUtils.isSystemHeader(headerName, true) && false == headerName.equals("Allow")
					&& false == headerName.equals("Call-Info")) {
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
