/// A test B2BUA application that acts as a User Agent Client, injecting custom
/// SIP headers into outbound INVITE requests. This module demonstrates how to
/// modify outbound requests in the B2BUA call lifecycle, including session timer
/// headers and custom vendor-specific headers.
///
/// ## Key Components
///
/// - [UserAgentClientServlet] - the main SIP servlet extending {@code B2buaServlet}, injecting configured headers into outbound requests
/// - [UserAgentClientConfig] - configuration class with a {@code headers} map for defining custom SIP headers
/// - [UserAgentClientConfigSample] - default configuration providing sample headers including session timers and a Genesys call UUID
///
/// ## Header Injection
///
/// ### UserAgentClientServlet
/// [UserAgentClientServlet] extends {@code B2buaServlet} and is annotated with
/// {@code @WebListener}, {@code @SipApplication(distributable = true)},
/// {@code @SipServlet(loadOnStartup = 1)}, and {@code @SipListener}. During
/// {@code callStarted()}, it iterates over the configured header map and sets
/// each header on the outbound SIP request. It also sets the {@code noKeepAlive}
/// attribute to {@code Boolean.TRUE} for testing keep-alive behavior.
///
/// ### Configuration-Driven Headers
/// All B2BUA lifecycle callbacks ({@code callAnswered}, {@code callConnected},
/// {@code callCompleted}, {@code callDeclined}, {@code callAbandoned}) are
/// implemented with info-level logging for tracing call flow.
///
/// ## Configuration
///
/// ### UserAgentClientConfig
/// Extends {@code Configuration} and defines a {@code headers} map
/// ({@code Map<String, String>}) for specifying SIP headers to inject on
/// outbound requests.
///
/// ### UserAgentClientConfigSample Defaults
/// Provides sample header values demonstrating common use cases:
///
/// - {@code Min-SE: 90} - minimum session expiration for session timers
/// - {@code Session-Expires: 2400;refresher=uac} - session timer with UAC as refresher
/// - {@code Supported: timer} - advertise session timer support
/// - {@code X-Genesys-CallUUID: 123potatoXYZ} - vendor-specific call correlation header
///
/// The default logging level is set to FINEST with a 180-second session expiration.
///
/// ## Related Packages
///
/// ### org.vorpal.blade.test.client
/// A SIP test client with a REST API for initiating outbound SIP calls. Provides
/// [TestClientServlet][org.vorpal.blade.test.client.TestClientServlet] extending
/// {@code AsyncSipServlet} for SIP request routing,
/// [TestClientAPI][org.vorpal.blade.test.client.TestClientAPI] as a JAX-RS endpoint
/// ({@code POST /api/v1/connect}) that bridges HTTP REST requests to SIP INVITE
/// sessions using asynchronous response handling, and data models
/// [MessageRequest][org.vorpal.blade.test.client.MessageRequest],
/// [MessageResponse][org.vorpal.blade.test.client.MessageResponse], and
/// [MessageSession][org.vorpal.blade.test.client.MessageSession] for REST-to-SIP
/// bridging.
///
/// ### org.vorpal.blade.framework.tpcc
/// Third-Party Call Control callflows and REST APIs enabling an external application
/// to set up, modify, and tear down SIP calls between two endpoints.
/// [Simple][org.vorpal.blade.framework.tpcc.Simple] implements the classic TPCC
/// pattern using dual INVITE sequences with blackhole SDP,
/// [TestReinvite][org.vorpal.blade.framework.tpcc.TestReinvite] handles mid-dialog
/// re-INVITE with 200 OK, and
/// [ThirdPartyCallControl][org.vorpal.blade.framework.tpcc.ThirdPartyCallControl]
/// exposes {@code POST /api/v1/tpcc} with OpenAPI annotations and asynchronous
/// HTTP response handling.
///
/// @see org.vorpal.blade.test.client
/// @see org.vorpal.blade.framework.tpcc
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
/// @see org.vorpal.blade.framework.v2.config.SettingsManager
/// @see org.vorpal.blade.framework.v2.config.Configuration
package org.vorpal.blade.test.uac;
