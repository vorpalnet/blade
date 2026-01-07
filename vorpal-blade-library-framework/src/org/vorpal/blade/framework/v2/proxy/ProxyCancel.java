package org.vorpal.blade.framework.v2.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/**
 * Callflow for handling CANCEL requests in proxy mode.
 * The container automatically handles CANCEL processing, so this is a no-op.
 */
public class ProxyCancel extends Callflow {
	private static final long serialVersionUID = 1L;

	@SuppressWarnings("unused")
	private final ProxyListener proxyListener;

	/**
	 * Creates a new ProxyCancel callflow.
	 *
	 * @param proxyListener the proxy listener (reserved for future use)
	 */
	public ProxyCancel(ProxyListener proxyListener) {
		this.proxyListener = proxyListener;
	}

	/**
	 * No-op implementation - the container handles CANCEL automatically.
	 *
	 * @param request the CANCEL request
	 * @throws ServletException if a servlet error occurs
	 * @throws IOException if an I/O error occurs
	 */
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// Container handles CANCEL automatically
	}
}
