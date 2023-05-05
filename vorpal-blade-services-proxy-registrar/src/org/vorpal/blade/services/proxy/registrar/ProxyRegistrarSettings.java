/**
 *  MIT License
 *  
 *  Copyright (c) 2016 Vorpal Networks, LLC
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

package org.vorpal.blade.services.proxy.registrar;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.Flow;
import javax.servlet.sip.Proxy;

import org.vorpal.blade.framework.config.Configuration;

public class ProxyRegistrarSettings extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * If no 'Allow' header is defined in the REGISTER method, use this value for
	 * 'Allow' for the 200 OK. Otherwise, echo back the 'Allow' header values.
	 */

	protected List<String> defaultAllow;

	/**
	 * Proxy the request to the 'To' header instead of issuing a 404 Not Found
	 * error.
	 */
	protected Boolean proxyOnUnregistered = false;

	/**
	 * Specifies whether branches initiated in this proxy operation should include a
	 * Path header for the REGISTER request for this servlet container or not.
	 */
	protected Boolean addToPath = false;

	/**
	 * Indicate that the specified flow be used while sending messages on this
	 * Proxy.
	 */
	protected Flow flow = null;

	/**
	 * Specifies whether the proxy should, or should not cancel outstanding branches
	 * upon receiving the first 2xx INVITE response as defined in RFC 3841.
	 */
	protected Boolean noCancel = false;

	/**
	 * In multi-homed environment this method can be used to select the outbound
	 * interface to use when sending requests for proxy branches.
	 */
	protected InetAddress outboundInterfaceAddress = null;

	/**
	 * In multi-homed environment this method can be used to select the outbound
	 * interface and port number to use for proxy branches.
	 */
	protected InetSocketAddress outboundInterfaceSocketAddress = null;

	/**
	 * Specifies whether to proxy in parallel or sequentially.
	 */
	protected Boolean parallel = true;

	/**
	 * Sets the overall proxy timeout in seconds.
	 */
	protected Integer proxyTimeout = null;

	/**
	 * Specifies whether branches initiated in this proxy operation should include a
	 * Record-Route header for this servlet engine or not. This shall affect all the
	 * branches created after its invocation. Record-routing is used to specify that
	 * this servlet engine must stay on the signaling path of subsequent requests.
	 */
	protected Boolean recordRoute = true;

	/**
	 * Specifies whether the servlet engine will automatically recurse or not. If
	 * recursion is enabled the servlet engine will automatically attempt to proxy
	 * to contact addresses received in redirect (3xx) responses. If recursion is
	 * disabled and no better response is received, a redirect response will be
	 * passed to the application and will be passed upstream towards the client.
	 */
	protected Boolean recurse = true;

	/**
	 * Specifies whether the controlling servlet is to be invoked for incoming
	 * responses relating to this proxying.
	 */
	protected Boolean supervised = true;

	public ProxyRegistrarSettings() {
		defaultAllow = new LinkedList<>();
		defaultAllow.add("OPTIONS");
		defaultAllow.add("SUBSCRIBE");
		defaultAllow.add("NOTIFY");
		defaultAllow.add("INVITE");
		defaultAllow.add("ACK");
		defaultAllow.add("CANCEL");
		defaultAllow.add("BYE");
		defaultAllow.add("REFER");
		defaultAllow.add("INFO");
	}

	/**
	 * Apply default configuration values to the 'proxy' object. Values include
	 * 'addToPath', 'flow', 'noCancel', 'outboundInterfaceAddress',
	 * outboundInterfaceSocketAddress', 'parallel', 'proxyTimeout', 'recordRoute',
	 * 'recurse' and 'supervised'.
	 * 
	 * @param proxy
	 * @return the update proxy object
	 */
	public Proxy apply(Proxy proxy) {

		if (this.addToPath != null) {
			proxy.setAddToPath(this.addToPath);
		}

		if (this.flow != null) {
			proxy.setFlow(this.flow);
		}

		if (this.noCancel != null) {
			proxy.setNoCancel(this.noCancel);
		}

		if (this.outboundInterfaceAddress != null) {
			proxy.setOutboundInterface(this.outboundInterfaceAddress);
		}

		if (this.outboundInterfaceSocketAddress != null) {
			proxy.setOutboundInterface(this.outboundInterfaceSocketAddress);
		}

		if (this.parallel != null) {
			proxy.setParallel(this.parallel);
		}

		if (this.proxyTimeout != null) {
			proxy.setProxyTimeout(this.proxyTimeout);
		}

		if (this.recordRoute != null) {
			proxy.setRecordRoute(this.recordRoute);
		}

		if (this.recurse != null) {
			proxy.setRecurse(this.recurse);
		}

		if (this.supervised != null) {
			proxy.setSupervised(this.supervised);
		}

		return proxy;
	}

	public List<String> getDefaultAllow() {
		return defaultAllow;
	}

	public void setDefaultAllow(List<String> defaultAllow) {
		this.defaultAllow = defaultAllow;
	}

	public Boolean getProxyOnUnregistered() {
		return proxyOnUnregistered;
	}

	public void setProxyOnUnregistered(Boolean proxyOnUnregistered) {
		this.proxyOnUnregistered = proxyOnUnregistered;
	}

	public Boolean getAddToPath() {
		return addToPath;
	}

	public void setAddToPath(Boolean addToPath) {
		this.addToPath = addToPath;
	}

	public Flow getFlow() {
		return flow;
	}

	public void setFlow(Flow flow) {
		this.flow = flow;
	}

	public Boolean getNoCancel() {
		return noCancel;
	}

	public void setNoCancel(Boolean noCancel) {
		this.noCancel = noCancel;
	}

	public InetAddress getOutboundInterfaceAddress() {
		return outboundInterfaceAddress;
	}

	public void setOutboundInterfaceAddress(InetAddress outboundInterfaceAddress) {
		this.outboundInterfaceAddress = outboundInterfaceAddress;
	}

	public InetSocketAddress getOutboundInterfaceSocketAddress() {
		return outboundInterfaceSocketAddress;
	}

	public void setOutboundInterfaceSocketAddress(InetSocketAddress outboundInterfaceSocketAddress) {
		this.outboundInterfaceSocketAddress = outboundInterfaceSocketAddress;
	}

	public Boolean getParallel() {
		return parallel;
	}

	public void setParallel(Boolean parallel) {
		this.parallel = parallel;
	}

	public Integer getProxyTimeout() {
		return proxyTimeout;
	}

	public void setProxyTimeout(Integer proxyTimeout) {
		this.proxyTimeout = proxyTimeout;
	}

	public Boolean getRecordRoute() {
		return recordRoute;
	}

	public void setRecordRoute(Boolean recordRoute) {
		this.recordRoute = recordRoute;
	}

	public Boolean getRecurse() {
		return recurse;
	}

	public void setRecurse(Boolean recurse) {
		this.recurse = recurse;
	}

	public Boolean getSupervised() {
		return supervised;
	}

	public void setSupervised(Boolean supervised) {
		this.supervised = supervised;
	}

}
