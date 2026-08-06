/// Implementations of the `javax.servlet.sip` interfaces that are **detached** —
/// no transaction, no dialog, no socket, no container behind them.
///
/// A [DetachedResponse] is a real `SipServletResponse` holding real headers and a
/// real body; it simply was not delivered by OCCAS. The framework ships three of
/// these at runtime:
///
/// - [org.vorpal.blade.framework.Callflow#sendRequest] hands your callback a
///   `DetachedResponse` carrying `500` when a callflow throws, so a callflow never
///   has to handle both an exception path and an error-response path.
/// - `v3.crud.SipMessageParser` turns SIP text into a detached request or response
///   for the CRUD preview, and `SipMessageSerializer` renders it back to RFC 3261.
/// - `v2.transfer.api.TransferAPI` builds a request the REST caller asked for
///   rather than one the network delivered.
///
/// They are also how you unit test a callflow. A `SipServlet` subclass cannot be
/// instantiated outside OCCAS, but a [org.vorpal.blade.framework.Callflow] is an
/// ordinary object: install these in place of the container services and a callflow
/// will build requests, link sessions, and run its callbacks in a plain JUnit test.
///
/// **Start with the README beside this file** — it has the setup and a worked
/// example. The short version:
///
/// ```java
/// Callflow.setSipFactory(new DetachedSipFactory());
/// Callflow.setSipLogger(new CapturingLogger());
/// Callflow.setSipUtil(new DetachedSipSessionsUtil());
/// ```
///
/// All three are required. Omitting [DetachedSipSessionsUtil] is the one that
/// misleads: `sendRequest` mints a Vorpal-ID on an initial INVITE, the missing util
/// throws, and `sendRequest`'s own error handling converts that into a synthetic
/// `500` delivered to your callback — so the test looks like a declined call rather
/// than a setup mistake.
///
/// ## The classes
///
/// - [DetachedSipFactory] — builds requests, URIs and addresses. Refuses ACK and
///   CANCEL with the same `IllegalArgumentException` OCCAS throws, since each must
///   be derived from the message it answers.
/// - [DetachedSipSessionsUtil] — application-session lookups over whatever has been
///   registered. Unregistered is not an error: an id-uniqueness check comes back
///   empty.
/// - [DetachedApplicationSession] — attribute storage, a registry of its
///   [DetachedSipSession]s, and index keys.
/// - [DetachedSipSession] — attributes, session state including `TERMINATED`,
///   `createRequest`, and active-INVITE tracking for `createCancel`.
/// - [DetachedMessage] — the shared base: headers, address headers, parameterable
///   headers, content, character encoding, network defaults.
/// - [DetachedRequest] — `createResponse`, `createCancel`, routing directive,
///   Max-Forwards, and a settable `isInitial` that defaults to true.
/// - [DetachedResponse] — extends [DetachedMessage]. Status and reason phrase,
///   `createAck`, `createPrack`, and a settable reliable-provisional flag. It seeds
///   its headers from the request it answers, then owns them: writing a header on a
///   response does not disturb the request.
/// - [DetachedSipURI] — parses and renders
///   `scheme:user:password@host:port;params?headers`, including flag parameters,
///   which must not gain an `=` on the way out.
/// - [DetachedAddress] — parses `"Alice" <sip:alice@example.com;transport=tcp>;tag=abc`,
///   keeping header parameters distinct from URI parameters. A `tag` must never
///   reach a request URI.
///
/// ## What they do not do
///
/// `send()` is a no-op everywhere, so nothing leaves the process and no response
/// arrives by itself — deliver one with `Callflow.pullCallback(response)` and invoke
/// the callback. Timers do not fire. There is no container dispatch, so call
/// `process(request)` yourself.
///
/// These are as correct as the code using them demands, and several methods became
/// real implementations only because a test needed them to be. Because the framework
/// ships three of them at runtime, a change here is a change to production
/// behaviour — not just to a test helper.
///
/// @see org.vorpal.blade.framework.Callflow
/// @see org.vorpal.blade.framework.v2.logging.CapturingLogger
package org.vorpal.blade.framework.sip;
