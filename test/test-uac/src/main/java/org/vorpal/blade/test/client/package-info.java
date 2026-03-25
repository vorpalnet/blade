/// A SIP test client application with a REST API for initiating outbound SIP calls.
/// This module provides a crude but functional test client that bridges HTTP REST
/// requests to SIP INVITE sessions, enabling automated testing of SIP services
/// from HTTP-based test harnesses.
///
/// ## Key Components
///
/// - [TestClientServlet] - the main SIP servlet extending {@code AsyncSipServlet}, routing requests to appropriate callflows
/// - [TestClientAPI] - JAX-RS REST endpoint ({@code POST /api/v1/connect}) that creates outbound SIP INVITE sessions from HTTP requests
/// - [TestClientBye] - callflow that handles BYE requests by sending a 200 OK response
/// - [TestClientConfig] - configuration class extending {@code Configuration} with a version placeholder
/// - [TestClientConfigDefault] - default configuration with FINER logging and 900-second session expiration
/// - [MessageRequest] - data model for inbound REST requests containing SIP addressing and content fields
/// - [MessageResponse] - data model for REST responses containing session ID, final status, and response history
/// - [MessageSession] - session wrapper linking a {@code SipApplicationSession}, {@code SipSession}, and JAX-RS {@code AsyncResponse}
/// - [Header] - simple name/value pair for SIP headers
///
/// ## REST-to-SIP Bridge
///
/// ### TestClientAPI Endpoint
/// [TestClientAPI] extends {@code Callflow} and exposes {@code POST /api/v1/connect}
/// with OpenAPI annotations. It accepts a [MessageRequest] containing {@code fromAddress},
/// {@code toAddress}, {@code requestURI}, and a list of [Header] objects. The endpoint:
///
/// 1. Creates a new {@code SipApplicationSession} and outbound INVITE request
/// 2. Sets custom headers from the request
/// 3. Attaches MIME multipart content (e.g., SIPREC metadata) to the SIP message
/// 4. Stores the JAX-RS {@code AsyncResponse} keyed by application session ID
/// 5. Sends the INVITE and resumes the HTTP response when a final SIP response arrives
///
/// ### Asynchronous Response Handling
/// The REST endpoint uses {@code @Suspended AsyncResponse} to hold the HTTP connection
/// open while SIP signaling completes. The async response map correlates SIP callbacks
/// back to the originating HTTP request.
///
/// ## Servlet and Callflows
///
/// ### TestClientServlet
/// [TestClientServlet] extends {@code AsyncSipServlet} and routes incoming SIP
/// requests to callflows based on method type: INVITE requests are handled by
/// [TestReinvite][org.vorpal.blade.framework.tpcc.TestReinvite], and BYE requests
/// by [TestClientBye]. Configuration is managed through a
/// [SettingsManager][org.vorpal.blade.framework.v2.config.SettingsManager].
///
/// ### TestClientBye Callflow
/// [TestClientBye] simply responds with 200 OK to any BYE request, cleanly
/// terminating the SIP session.
///
/// ## Data Models
///
/// ### MessageRequest
/// Contains fields for constructing a SIP request: {@code requestURI},
/// {@code fromAddress}, {@code toAddress}, a list of [Header] objects,
/// {@code contentType}, and {@code content}.
///
/// ### MessageResponse
/// Captures the outcome of a SIP transaction: {@code id} for session correlation,
/// {@code finalStatus} for the SIP response code, and a list of {@code responses}
/// containing the full text of each SIP response received.
///
/// ### MessageSession
/// Wraps the SIP session state along with the JAX-RS {@code AsyncResponse}, using
/// a hash of the combined application and SIP session IDs as a unique key.
///
/// @see org.vorpal.blade.framework.v2.AsyncSipServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.framework.v2.config.SettingsManager
package org.vorpal.blade.test.client;
