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

package org.vorpal.blade.services.options;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

@SchemaAbout(
		name = "Options",
		tagline = "SIP OPTIONS Keep-Alive Responder",
		description = "Answers inbound SIP OPTIONS pings — the keep-alive and health probes "
				+ "upstream elements send to confirm the server is alive — with configurable "
				+ "responses, so monitors and SBCs see the node as in-service.")
public class OptionsSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Media types accepted by this server, used in the Accept header of OPTIONS responses")
	protected String accept;

	@JsonPropertyDescription("Preferred languages for the response, used in the Accept-Language header of OPTIONS responses")
	protected String acceptLanguage;

	@JsonPropertyDescription("SIP methods supported by this server, used in the Allow header of OPTIONS responses")
	protected String allow;

	@JsonPropertyDescription("SIP extensions supported by this server, used in the Supported header of OPTIONS responses")
	protected String supported;

	@JsonPropertyDescription("Identifies this SIP user agent, used in the User-Agent header of OPTIONS responses")
	protected String userAgent;

	@JsonPropertyDescription("Event packages supported by this server, used in the Allow-Events header of OPTIONS responses")
	protected String allowEvents;

	protected boolean unavailableWhenOverloaded;

	protected int overloadRetryAfter;

	public String getAllow() {
		return allow;
	}

	public void setAllow(String allow) {
		this.allow = allow;
	}

	public String getAccept() {
		return accept;
	}

	public void setAccept(String accept) {
		this.accept = accept;
	}

	public String getAcceptLanguage() {
		return acceptLanguage;
	}

	public void setAcceptLanguage(String acceptLanguage) {
		this.acceptLanguage = acceptLanguage;
	}

	public String getSupported() {
		return supported;
	}

	public void setSupported(String supported) {
		this.supported = supported;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public String getAllowEvents() {
		return allowEvents;
	}

	public void setAllowEvents(String allowEvents) {
		this.allowEvents = allowEvents;
	}

	@JsonPropertyDescription("When true, OPTIONS answers 503 Service Unavailable while OCCAS overload protection is actively rejecting traffic, so a SIP-aware load balancer drains this node. When false — or when no overload thresholds are configured — OPTIONS always answers 200 OK, exactly as before.")
	public boolean isUnavailableWhenOverloaded() {
		return unavailableWhenOverloaded;
	}

	public void setUnavailableWhenOverloaded(boolean unavailableWhenOverloaded) {
		this.unavailableWhenOverloaded = unavailableWhenOverloaded;
	}

	@JsonPropertyDescription("Seconds advertised in the Retry-After header of the 503 sent while overloaded, telling the peer/load balancer how long to wait before retrying. 0 omits the header.")
	public int getOverloadRetryAfter() {
		return overloadRetryAfter;
	}

	public void setOverloadRetryAfter(int overloadRetryAfter) {
		this.overloadRetryAfter = overloadRetryAfter;
	}

}
