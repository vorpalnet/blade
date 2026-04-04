/// This package implements a SIP Application Router (SAR) based on JSR-289's
/// `javax.servlet.sip.ar.SipApplicationRouter` interface. FSMAR2 uses a finite state machine
/// model to determine which SIP servlet application should handle each incoming request,
/// supporting configurable routing rules with condition-based transitions.
///
/// ## Key Components
///
/// - [AppRouter] - Core SIP application router implementing `SipApplicationRouter`
/// - [AppRouterProvider] - SPI provider that exposes [AppRouter] to the SIP servlet container
/// - [AppRouterConfiguration] - JSON-serializable configuration defining the routing state machine
/// - [AppRouterConfigurationSample] - Generates a sample configuration file with example routing rules
/// - [State] - Represents a state in the routing FSM, keyed by the previously invoked application
/// - [Trigger] - Maps SIP method names to lists of [Transition] objects within a [State]
/// - [Transition] - Defines a conditional routing rule with an optional [Action] and target application
/// - [Action] - Specifies routing parameters including region, subscriber URI, and route modifiers
///
/// ## Routing State Machine
///
/// ### State Resolution
///
/// The router determines the current [State] based on the name of the previously invoked SIP
/// application (obtained from the `SipApplicationSessionImpl`). A special `"null"` state represents
/// the initial state when no application has yet processed the request. States are stored in the
/// [AppRouterConfiguration] as a `HashMap` keyed by application basename.
///
/// ### Trigger Matching
///
/// Within each [State], a [Trigger] is looked up by SIP method name (e.g., "INVITE", "REGISTER").
/// Each [Trigger] contains an ordered list of [Transition] objects that are evaluated sequentially.
/// The first [Transition] whose conditions match the request wins.
///
/// ### Transition Conditions
///
/// Each [Transition] can optionally carry a `Condition` object (from the framework config package)
/// that evaluates SIP headers using various comparison operators: `equals`, `contains`, `includes`,
/// `uri`, `address`, `user`, `host`, `value`, and custom parameter extractors. If no condition is
/// specified, the transition matches unconditionally. Conditions are checked via `checkAll()`,
/// which requires all comparisons to pass.
///
/// ### Actions and Route Modifiers
///
/// When a [Transition] matches, its [Action] creates a `SipApplicationRouterInfo` with:
///
/// - **Routing Region**: ORIGINATING, TERMINATING, or NEUTRAL, determined by which address header
///   (e.g., "From" or "To") is specified in the action's `originating` or `terminating` field
/// - **Subscriber URI**: Extracted from the specified address header in the SIP request
/// - **Route Modifier**: NO_ROUTE (default), ROUTE, ROUTE_BACK, or ROUTE_FINAL, each with an
///   associated array of route URIs (e.g., `["sip:proxy1", "sip:proxy2"]`)
///
/// ### Default Application Fallback
///
/// If no transition matches for an initial request (previous application is `"null"`), the router
/// falls back to the `defaultApplication` defined in [AppRouterConfiguration]. This ensures that
/// every incoming SIP request is routed to at least one application.
///
/// ## Special Routing Cases
///
/// ### Targeted Requests
///
/// When the container provides `SipTargetedRequestInfo` (e.g., for REFER or dialog-creating
/// requests targeting a specific application), the router bypasses the FSM and routes directly
/// to the targeted application using the current routing region.
///
/// ### URI-Based Application Resolution
///
/// If the SIP Request-URI has no user part (e.g., `sip:hold`), the router checks if the host
/// portion matches a deployed application name. This allows direct application targeting via
/// SIP URIs without going through the state machine.
///
/// ## Configuration and Deployment
///
/// ### JSON Configuration
///
/// [AppRouterConfiguration] extends the framework `Configuration` base class and is managed
/// through `SettingsManager`. The configuration is serialized as JSON using Jackson, with
/// null-value exclusion for compact representation. The `FSMAR2.SAMPLE` file is generated
/// by [AppRouterConfigurationSample] with example routing rules demonstrating all supported
/// condition operators and route modifiers.
///
/// ### SPI Integration
///
/// [AppRouterProvider] extends `SipApplicationRouterProvider` (JSR-289 SPI) and holds a single
/// static [AppRouter] instance. The container discovers this provider via the standard
/// `META-INF/services` mechanism and calls `getSipApplicationRouter()` to obtain the router.
///
/// ### Application Lifecycle
///
/// [AppRouter] tracks deployed applications in a static `HashMap` mapping application basenames
/// to their full deployment names. Applications are registered via `applicationDeployed()` and
/// the mapping is used to resolve short names in the configuration to full deployment names
/// when creating `SipApplicationRouterInfo` instances.
///
/// @see javax.servlet.sip.ar.SipApplicationRouter
/// @see javax.servlet.sip.ar.SipApplicationRouterInfo
/// @see javax.servlet.sip.ar.spi.SipApplicationRouterProvider
/// @see org.vorpal.blade.framework.v2.config.Configuration
/// @see org.vorpal.blade.framework.v2.config.Condition
package org.vorpal.blade.library.fsmar2;
