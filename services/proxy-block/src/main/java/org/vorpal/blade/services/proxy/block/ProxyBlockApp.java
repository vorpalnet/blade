package org.vorpal.blade.services.proxy.block;

import org.vorpal.blade.framework.v3.irouter.IRouterConfig;
import org.vorpal.blade.framework.v3.irouter.IRouterServlet;

/// Annotated leaf for the call-blocking WAR: the iRouter under another name.
/// Every decision lives in the configuration; this class only names the app
/// and supplies its sample.
///
/// The SIP application name stays `block`, the name the original proxy-block
/// deployed under, so an existing FSMAR configuration that targets `block`
/// still reaches it.
@javax.servlet.sip.annotation.SipApplication(name = "block", distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyBlockApp extends IRouterServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected Class<? extends IRouterConfig> configClass() {
		return CallBlockingConfig.class;
	}

	@Override
	protected IRouterConfig newSampleConfig() {
		return new CallBlockingConfigSample();
	}
}
