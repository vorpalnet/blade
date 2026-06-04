package org.vorpal.blade.services.irouter;

import org.vorpal.blade.framework.v3.irouter.IRouterServlet;

/// Annotated leaf that activates the framework's [IRouterServlet] as the SIP
/// servlet for the standalone iRouter WAR. The annotations live here (not on
/// [IRouterServlet]) so commercial extensions can subclass [IRouterServlet]
/// without picking up duplicate `@SipApplication` / `@SipServlet`
/// registrations from the base. Each leaf WAR ships its own annotated leaf.
///
/// Empty body: the base's default seams ([IRouterServlet#newSampleConfig] /
/// [IRouterServlet#newInvite]) already supply plain iRouter behavior.
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class IRouterApp extends IRouterServlet {
	private static final long serialVersionUID = 1L;
}
