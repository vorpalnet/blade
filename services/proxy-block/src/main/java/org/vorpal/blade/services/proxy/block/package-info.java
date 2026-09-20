/// Call blocking: the iRouter with a call-screening sample and its own name.
///
/// There is no call-blocking code here. [ProxyBlockApp] is an
/// [org.vorpal.blade.framework.v3.irouter.IRouterServlet] leaf,
/// [CallBlockingConfig] renames the config type for the Configurator and
/// Portal, and [CallBlockingConfigSample] is the screening policy expressed as
/// iRouter connectors and conditional routing. Anything the sample does, an
/// operator changes in the Configurator without a rebuild.
///
/// The application name is `block` and the context root `proxy-block`, both
/// kept from the original module so deployments and FSMAR configurations that
/// name them still resolve. A configuration written for the original module
/// (`callingNumbers`, `fromSelector`, `defaultRoute`) does not load; it must be
/// rewritten as a pipeline and routing.
package org.vorpal.blade.services.proxy.block;
