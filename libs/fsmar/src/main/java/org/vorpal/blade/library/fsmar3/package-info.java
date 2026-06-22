/// FSMAR v3 runtime — the engine that runs the FSMAR state machine inside
/// OCCAS. Packaged as a fat JAR dropped into `$DOMAIN_HOME/approuter/` and
/// loaded by the WLSS Application Router via the
/// `javax.servlet.sip.ar.spi.SipApplicationRouterProvider` SPI.
///
/// - [AppRouter] — the [javax.servlet.sip.ar.SipApplicationRouter]
///   implementation: evaluates the FSM each hop, runs each state's selectors,
///   matches transitions, and builds the routing decision.
/// - [AppRouterProvider] — the SPI provider that hands the container the
///   singleton [AppRouter].
/// - [Fsmar3Metrics] / [Fsmar3MetricsMBean] — routing metrics and opt-in
///   trace capture, exposed over JMX.
///
/// The configuration data model it routes on
/// ([org.vorpal.blade.framework.v3.fsmar.AppRouterConfiguration] and friends)
/// lives in the framework JAR so admin-tier tools can reuse it.
package org.vorpal.blade.library.fsmar3;
