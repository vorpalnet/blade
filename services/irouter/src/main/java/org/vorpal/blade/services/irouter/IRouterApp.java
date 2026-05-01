package org.vorpal.blade.services.irouter;

/// Annotated leaf class that activates [IRouterServlet] as the SIP
/// servlet for the standalone iRouter WAR. The annotations live here
/// (not on [IRouterServlet]) so commercial extensions like
/// connect/securelogix can subclass [IRouterServlet] without picking
/// up duplicate `@SipApplication`/`@SipServlet` registrations from a
/// transitively-bundled base class. Each leaf WAR ships its own
/// annotated leaf.
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class IRouterApp extends IRouterServlet {
	private static final long serialVersionUID = 1L;
}
