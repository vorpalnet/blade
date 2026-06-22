/// FSMAR v2 runtime (legacy) — the engine that runs the FSMAR 2 state machine
/// inside OCCAS. Packaged as a fat JAR dropped into `$DOMAIN_HOME/approuter/`
/// and loaded by the WLSS Application Router via the
/// `javax.servlet.sip.ar.spi.SipApplicationRouterProvider` SPI.
///
/// - [AppRouter] — the [javax.servlet.sip.ar.SipApplicationRouter]
///   implementation.
/// - [AppRouterProvider] — the SPI provider that hands the container the
///   singleton [AppRouter].
///
/// The configuration data model it routes on
/// ([org.vorpal.blade.framework.v2.fsmar.AppRouterConfiguration] and friends)
/// lives in the framework JAR. FSMAR 2 is retained for backward compatibility;
/// FSMAR 3 is the current router.
package org.vorpal.blade.library.fsmar2;
