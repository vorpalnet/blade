/// Mock implementations of the SIP servlet interfaces, so callflows can be unit
/// tested without a SIP container.
///
/// A `SipServlet` subclass cannot be instantiated outside OCCAS, but a
/// [org.vorpal.blade.framework.Callflow] is an ordinary object. Install the
/// doubles below in place of the container services and a callflow will build
/// requests, link sessions, and run its callbacks in a plain JUnit test.
///
/// **Start with the testing README** (`README.md` beside this file) — it has the
/// required setup and a worked example. The short version:
///
/// ```java
/// Callflow.setSipFactory(new DummySipFactory());
/// Callflow.setSipLogger(new CapturingLogger());
/// Callflow.setSipUtil(new DummySipSessionsUtil());
/// ```
///
/// All three are required. Omitting [DummySipSessionsUtil] is the one that
/// misleads: `sendRequest` mints a Vorpal-ID on an initial INVITE, the missing
/// util throws, and `sendRequest`'s own error handling converts that into a
/// synthetic `500` delivered to your callback — so the test looks like a
/// declined call rather than a setup mistake.
///
/// ## The doubles
///
/// - [DummySipFactory] — builds requests, URIs and addresses. Refuses ACK and
///   CANCEL with the same `IllegalArgumentException` OCCAS throws, since each
///   must be derived from the message it answers.
/// - [DummySipSessionsUtil] — application-session lookups, backed by whatever
///   has been registered. Unregistered is not an error: an id-uniqueness check
///   simply comes back empty.
/// - [DummyApplicationSession] — attribute storage, a registry of its
///   [DummySipSession]s, and index keys.
/// - [DummySipSession] — attributes, session state including `TERMINATED`,
///   `createRequest`, and active-INVITE tracking for `createCancel`.
/// - [DummyMessage] — the shared base class: headers, address headers,
///   parameterable headers, content, character encoding, network defaults.
/// - [DummyRequest] — `createResponse`, `createCancel`, routing directive,
///   Max-Forwards, and a settable `isInitial` that defaults to true.
/// - [DummyResponse] — extends [DummyMessage]. Status and reason phrase,
///   `createAck`, `createPrack`, and a settable reliable-provisional flag. It
///   seeds its headers from the request it answers, then owns them: writing a
///   header on a response does not disturb the request.
/// - [DummySipURI] — parses and renders `scheme:user:password@host:port;params?headers`,
///   including flag parameters, which must not gain an `=` on the way out.
/// - [DummyAddress] — parses `"Alice" <sip:alice@example.com;transport=tcp>;tag=abc`,
///   keeping header parameters distinct from URI parameters. A `tag` must never
///   reach a request URI.
///
/// ## What they do not do
///
/// `send()` is a no-op everywhere, so nothing leaves the test and no response
/// arrives by itself — deliver one with `Callflow.pullCallback(response)` and
/// invoke the callback. Timers do not fire. There is no container dispatch, so
/// call `process(request)` yourself.
///
/// These are test doubles, not a SIP stack: they are as correct as the tests
/// using them require. Several methods became real implementations only because
/// a test needed them to be, and that is the right way to extend them.
///
/// @see org.vorpal.blade.framework.Callflow
/// @see org.vorpal.blade.framework.v2.logging.CapturingLogger
package org.vorpal.blade.framework.v2.testing;
