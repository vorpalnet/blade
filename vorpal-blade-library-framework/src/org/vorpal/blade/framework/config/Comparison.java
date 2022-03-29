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
	Logger sipLogger; 

	public Comparison() {
		sipLogger = SettingsManager.getSipLogger();
	}

	public Comparison(String operator, String expression) {
		this.put(operator, expression);
	}

	@Override
	public boolean check(String name, SipServletRequest request) throws ServletParseException {
		boolean match = true;
		boolean isRequestURI = name.equalsIgnoreCase("Request-URI");

		String operator, expression, value=null;
		for (Entry<String, String> entry : this.entrySet()) {
			operator = entry.getKey();
			expression = entry.getValue();

			switch (operator) {
			case "address":
			case "matches":

				value = (isRequestURI) ? request.getRequestURI().toString() : request.getHeader(name);

				if (value != null) {
					match = match && value.matches(expression);
				} else {
					match = false;
				}

				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;
				
			case "uri":
				value = (isRequestURI) ? request.getRequestURI().toString() : request.getAddressHeader(name).getURI().toString();
				match = match && value.equalsIgnoreCase(expression);
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			case "user":
				value = (isRequestURI) ? ((SipURI) request.getRequestURI()).getUser()
						: ((SipURI) request.getAddressHeader(name).getURI()).getUser();
				if (value != null) {
					match = match && value.equalsIgnoreCase(expression);
				} else {
					match = false;
				}
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			case "host":
				value = (isRequestURI) ? ((SipURI) request.getRequestURI()).getHost()
						: ((SipURI) request.getAddressHeader(name).getURI()).getHost();
				match = match && value.equalsIgnoreCase(expression);
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			case "equals":
				value = (isRequestURI) ? request.getRequestURI().toString() : request.getHeader(name);
				if (value != null) {
					match = match && value.equalsIgnoreCase(expression);
				} else {
					match = false;
				}
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			case "contains":
				value = (isRequestURI) ? request.getRequestURI().toString() : request.getHeader(name);
				if (value != null) {
					match = match && value.contains(expression);
				} else {
					match = false;
				}
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			case "includes":
				boolean includes = false;

				Iterator<String> itr = request.getHeaders(name);
				while (itr.hasNext()) {
					value = itr.next();

					if (value.equalsIgnoreCase(expression)) {
						includes = true;
						break;
					}
				}

				match = match && includes;
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			case "value":
				boolean hasValue = false;

				Parameterable p;
				Iterator<? extends Parameterable> pItr = request.getParameterableHeaders(name);
				while (pItr.hasNext()) {
					value = pItr.next().getValue();

					if (value.equalsIgnoreCase(expression)) {
						includes = true;
						break;
					}
				}

				match = match && hasValue;
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			default:

				boolean hasParam = false;

				if (isRequestURI) {
					String param = request.getRequestURI().getParameter(operator);
					if (param != null) {
						if (param.equalsIgnoreCase(expression)) {
							hasParam = true;
						}
					}
				} else {

					Parameterable p2;
					Iterator<? extends Parameterable> p2Itr = request.getParameterableHeaders(name);
					while (p2Itr.hasNext()) {
						value = p2Itr.next().getParameter(operator);

						if (value != null) {
							if (value.equalsIgnoreCase(expression)) {
								hasParam = true;
								break;
							}
						}
					}
				}

				match = match && hasParam;
				sipLogger.finer("Comparing " + name + ": " + value + ", " + operator + " " + expression + ": " + match);
				break;

			}

			if (!match)
				break;

		}

		return match;
	}

}
