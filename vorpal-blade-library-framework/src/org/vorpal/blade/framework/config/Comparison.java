/**
 *  MIT License
 *  
 *  Copyright (c) 2013, 2022 Vorpal Networks, LLC
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

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;

import javax.servlet.sip.Parameterable;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.logging.Logger;

public class Comparison extends HashMap<String, String> implements RequestCondition, Serializable {

	public Comparison() {
	}

	public Comparison(String operator, String expression) {
		this.put(operator, expression);
	}

	@Override
	public boolean check(String id, String name, SipServletRequest request) throws ServletParseException {

		Logger sipLogger = SettingsManager.getSipLogger();

		boolean match = true;

		// assign these in advance for special conditions
		String value = null;
		SipURI sipUri = null;

		switch (name) {
		case "Request-URI":
			value = request.getRequestURI().toString();
			sipUri = (SipURI) request.getRequestURI();
			break;
		case "Directive":
			value = request.getRoutingDirective().toString();
			break;
		case "Region":
			value = request.getRegion().getType().toString();
			break;
		case "Region-Label":
			value = request.getRegion().getLabel().toString();
			break;
		}

		String operator = null, expression = null;

		for (Entry<String, String> entry : this.entrySet()) {
			operator = entry.getKey();
			expression = entry.getValue();

			switch (operator) {
			case "address":
			case "matches":

				value = (value != null) ? value : request.getHeader(name);
				if (value != null) {
					match = match && value.matches(expression);
				} else {
					match = false;
				}

				break;

			case "uri":
				value = (value != null) ? value : request.getAddressHeader(name).getURI().toString();
				match = match && value.matches(expression);

				break;

			case "user":

				value = (sipUri != null) ? sipUri.getUser()
						: ((SipURI) request.getAddressHeader(name).getURI()).getUser();

				if (value != null) {
					match = match && value.equalsIgnoreCase(expression);
				} else {
					match = false;
				}

				break;

			case "host":

				value = (sipUri != null) ? sipUri.getHost()
						: ((SipURI) request.getAddressHeader(name).getURI()).getHost();
				match = match && value.equalsIgnoreCase(expression);

				break;

			case "equals":
				value = (value != null) ? value : request.getHeader(name);
				if (value != null) {
					match = match && value.equalsIgnoreCase(expression);
				} else {
					match = false;
				}

				break;

			case "contains":
				boolean contains = false;

				if (value != null) {
					contains = value.contains(expression);
				} else {
					Iterator<String> itr = request.getHeaders(name);
					while (itr.hasNext()) {
						value = itr.next();

						contains = value.contains(expression);

						sipLogger.finer("checking id=" + id + ", name=" + name + ", value=" + value + ", operator="
								+ operator + ", expression=" + expression + ", contains=" + contains);

						if (contains == true) {
							break;
						}
					}
				}

				match = match && contains;
				break;

			case "includes":
				boolean includes = false;

				if (value != null) {
					includes = value.equalsIgnoreCase(expression);
				} else {
					Iterator<String> inc_itr = request.getHeaders(name);
					while (inc_itr.hasNext()) {
						value = inc_itr.next();

						sipLogger.finer("checking id=" + id + ", name=" + name + ", value=" + value + ", operator="
								+ operator + ", expression=" + expression + ", includes=" + includes);
						if (includes == true) {
							break;
						}
					}
				}

				match = match && includes;
				break;

			case "value":
				boolean hasValue = false;

				if (value != null) {
					hasValue = value.equalsIgnoreCase(expression);
				} else {
					Parameterable p;
					Iterator<? extends Parameterable> pItr = request.getParameterableHeaders(name);
					while (pItr.hasNext()) {
						value = pItr.next().getValue();

						hasValue = value.equalsIgnoreCase(expression);

						sipLogger.finer("checking id=" + id + ", name=" + name + ", value=" + value + ", operator="
								+ operator + ", expression=" + expression + ", hasValue=" + hasValue);

						if (hasValue == true) {
							break;
						}

					}
				}

				match = match && hasValue;
				break;

			default:

				boolean hasParam = false;

				if (sipUri != null) {
					String param = sipUri.getParameter(operator);
					if (param != null) {

						hasParam = param.equalsIgnoreCase(expression);

						sipLogger.finer("checking id=" + id + ", name=" + name + ", value=" + value + ", operator="
								+ operator + ", expression=" + expression + ", hasParam=" + hasParam);

					}
				} else {

					Parameterable p2;
					Iterator<? extends Parameterable> p2Itr = request.getParameterableHeaders(name);
					while (p2Itr.hasNext()) {
						p2 = p2Itr.next();
						value = p2.getParameter(operator);

						if (value != null) {
							hasParam = value.equalsIgnoreCase(expression);

							sipLogger.finer("checking id=" + id + ", name=" + name + ", value=" + value + ", operator="
									+ operator + ", expression=" + expression + ", hasParam=" + hasParam);

							if (hasParam == true) {
								break;
							}

						}

					}
				}

				match = match && hasParam;
				break;

			}

			if (id != null) {
				sipLogger.finer("checking id=" + id + ", name=" + name + ", value=" + value + ", operator="
						+ ", expression=" + expression + ", match=" + match);
			}

			if (!match) {
				break;
			}

		}

		return match;
	}



}
