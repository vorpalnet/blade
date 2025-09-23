package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.Configuration;

//public class PRegistrarSettings extends Configuration{
public class Settings extends Configuration implements Serializable {
	private static final long serialVersionUID = -3362129920431974760L;
	
	/**
	 * Specifies the SIP messages this container supports.
	 */
	protected String allowHeader;

	/**
	 * Specifies whether branches initiated in this proxy operation should include a
	 * Path header for the REGISTER request for this servlet container or not.
	 */
	protected Boolean addToPath;

	/**
	 * Proxy the request to the 'To' header instead of issuing a 404 Not Found
	 * error.
	 */
	public Boolean proxyOnUnregistered;

	/**
	 * In multi-homed environment this method can be used to select the outbound
	 * interface to use when sending requests for proxy branches.
	 */
	public InetAddress outboundInterfaceAddress;

	/**
	 * In multi-homed environment this method can be used to select the outbound
	 * interface and port number to use for proxy branches.
	 */
	public InetSocketAddress outboundInterfaceSocketAddress;

	/**
	 * Specifies whether to proxy in parallel or sequentially.
	 */
	public Boolean parallel;

	/**
	 * Sets the overall proxy timeout in seconds.
	 */

	public Integer proxyTimeout;
	/**
	 * Specifies whether branches initiated in this proxy operation should include a
	 * Record-Route header for this servlet engine or not. This shall affect all the
	 * branches created after its invocation. Record-routing is used to specify that
	 * this servlet engine must stay on the signaling path of subsequent requests. Set to
	 * 'false' to enable 'loose-routing'.
	 */
	public Boolean recordRoute;

	/**
	 * Sets the proxy timeout in seconds.
	 */
	public Integer timeout = null;

	/**
	 * Specifies whether the application will be invoked on incoming responses
	 * related to this proxying.
	 */
	public Boolean supervised = null;

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
		SipServletRequest request = proxy.getOriginalRequest();

		if (this.addToPath != null) {
			Callflow.getSipLogger().finer(request, "proxy.setAddToPath(" + this.addToPath + ");");
			proxy.setAddToPath(this.addToPath);
		}

		if (this.outboundInterfaceAddress != null) {
			Callflow.getSipLogger().finer(request,
					"proxy.setOutboundInterface(" + this.outboundInterfaceAddress + ");");
			proxy.setOutboundInterface(this.outboundInterfaceAddress);
		}

		if (this.outboundInterfaceSocketAddress != null) {
			Callflow.getSipLogger().finer(request,
					"proxy.setOutboundInterface(" + this.outboundInterfaceSocketAddress + ");");
			proxy.setOutboundInterface(this.outboundInterfaceSocketAddress);
		}

		if (this.parallel != null) {
			Callflow.getSipLogger().finer(request, "proxy.setParallel(" + this.parallel + ");");
			proxy.setParallel(this.parallel);
		}

		if (this.proxyTimeout != null) {
			Callflow.getSipLogger().finer(request, "proxy.setProxyTimeout(" + this.proxyTimeout + ");");
			proxy.setProxyTimeout(this.proxyTimeout);
		}

		if (this.recordRoute != null) {
			Callflow.getSipLogger().finer(request, "proxy.setRecordRoute(" + this.recordRoute + ");");
			proxy.setRecordRoute(this.recordRoute);
		}

		if (this.supervised != null) {
			Callflow.getSipLogger().finer(request, "proxy.setSupervised(" + this.supervised + ");");
			proxy.setSupervised(this.supervised);
		}

		return proxy;
	}

}
