/// The heart of BLADE: Java Lambda Expression based callflows that
/// simplifies SIP Servlet development for telecommunication services.
///
/// ## The Lambda Callflow Pattern
///
/// Traditional SIP servlet development scatters call logic across disconnected
/// handler methods &mdash; {@code doInvite()}, {@code doResponse()}, {@code doAck()},
/// {@code doBye()} &mdash; with call state manually stored in session attributes.
/// The developer must trace attribute breadcrumbs across methods and classes to
/// understand the flow.
///
/// BLADE callflows replace this with readable, top-to-bottom code. The entire SIP
/// message exchange is expressed as nested lambda expressions:
///
/// {@snippet :
/// sendRequest(bobInvite, (bobResponse) -> {
///     SipServletResponse aliceResponse = createResponse(bobResponse, aliceRequest);
///     sendResponse(aliceResponse, (aliceAck) -> {
///         SipServletRequest bobAck = createAcknowlegement(bobResponse, aliceAck);
///         sendRequest(bobAck);
///     });
/// });
/// }
///
/// The nested lambdas mirror the actual SIP message exchange: send a request, wait
/// for the response, send back a response, wait for the ACK. The code reads exactly
/// like a SIP call flow diagram.
///
///
/// ## How Callbacks Work
///
/// The two fundamental methods are
/// {@link Callflow#sendRequest(javax.servlet.sip.SipServletRequest, Callback) sendRequest()}
/// and {@link Callflow#sendResponse(javax.servlet.sip.SipServletResponse, Callback) sendResponse()}.
/// Each accepts a SIP message and a [Callback] lambda:
///
/// <ul>
///   <li><b>{@code sendRequest(request, callback)}</b> &mdash; sends the request and
///       stores the callback in the SIP session, keyed by the request method
///       (e.g. {@code RESPONSE_CALLBACK_INVITE}). When a response arrives, the
///       framework retrieves and invokes the callback with that response.</li>
///   <li><b>{@code sendResponse(response, callback)}</b> &mdash; sends the response and
///       stores the callback for the subsequent ACK or PRACK
///       (e.g. {@code REQUEST_CALLBACK_ACK}). When the ACK arrives, the
///       framework retrieves and invokes the callback.</li>
/// </ul>
///
/// Both methods also have fire-and-forget variants that take no callback:
/// {@code sendRequest(request)} and {@code sendResponse(response)}.
///
/// ### Multiple Responses
///
/// Since there can be multiple responses to a single request (e.g. 180 Ringing
/// followed by 200 OK), the lambda within {@code sendRequest()} can be invoked
/// multiple times. The callback is only removed from the session when a
/// <em>final</em> response (status &ge; 200) arrives.
///
/// By contrast, the lambda within {@code sendResponse()} fires only once &mdash;
/// when the ACK (or PRACK) arrives.
///
///
/// ## Automatic State Serialization
///
/// [Callflow] implements {@code Serializable}. The [Callback] interface extends
/// both {@code Consumer} and {@code Serializable}. This means the lambda callbacks
/// and all variables they close over ({@code aliceRequest}, {@code bobResponse}, etc.)
/// are automatically persisted into SIP session memory by the container.
///
/// In a distributed cluster:
///
/// <ul>
///   <li>No {@code setAttribute()}/{@code getAttribute()} calls needed</li>
///   <li>Node failover preserves the entire callflow state</li>
///   <li>The call resumes on another node exactly where it left off</li>
/// </ul>
///
/// This is what makes BLADE unique: the developer writes natural, sequential code,
/// and the framework handles distributed state persistence transparently.
///
/// <p><b>Important:</b> all variables captured by a lambda must be serializable.
/// Standard SIP objects ({@code SipServletRequest}, {@code SipServletResponse},
/// {@code SipSession}) are serializable by the container. If you capture your own
/// objects, they must implement {@code Serializable} too.</p>
///
///
/// ## Writing a Callflow
///
/// Every callflow extends [Callflow] and implements the abstract
/// {@link Callflow#process(javax.servlet.sip.SipServletRequest) process()} method.
/// The framework calls {@code process()} when the application's
/// {@link org.vorpal.blade.framework.v2.AsyncSipServlet#chooseCallflow chooseCallflow()}
/// selects this callflow for an incoming request:
///
/// {@snippet :
/// public class MyCallflow extends Callflow {
///     @Override
///     public void process(SipServletRequest request) throws ServletException, IOException {
///         // Your call logic here — use sendRequest(), sendResponse(), etc.
///     }
/// }
/// }
///
///
/// ## B2BUA Patterns
///
/// The most common BLADE pattern is the Back-to-Back User Agent (B2BUA), where
/// the application sits between two call legs and mediates the SIP signaling.
/// The framework provides pre-built callflows for each phase of a B2BUA call:
///
/// ### Re-INVITE (mid-dialog)
///
/// The {@link org.vorpal.blade.framework.v2.b2bua.Reinvite Reinvite} callflow
/// demonstrates the canonical three-step lambda pattern. When Alice sends a
/// re-INVITE (e.g. to change media), the B2BUA forwards it to Bob:
///
/// {@snippet :
/// public void process(SipServletRequest aliceRequest) throws ServletException, IOException {
///     SipSession bobSession = getLinkedSession(aliceRequest.getSession());
///     SipServletRequest bobRequest = bobSession.createRequest(INVITE);
///     copyContentAndHeaders(aliceRequest, bobRequest);
///
///     sendRequest(bobRequest, (bobResponse) -> {                          // Step 1: forward to Bob
///         SipServletResponse aliceResponse = createResponse(bobResponse, aliceRequest);
///
///         sendResponse(aliceResponse, (aliceAck) -> {                     // Step 2: relay response to Alice
///             SipServletRequest bobAck = createAcknowlegement(bobResponse, aliceAck);
///             sendRequest(bobAck);                                        // Step 3: forward ACK to Bob
///         });
///     });
/// }
/// }
///
/// Three nested levels, three SIP message exchanges. The indentation of the code
/// matches the depth of the conversation.
///
/// ### Passthru (INFO, OPTIONS, MESSAGE)
///
/// For mid-dialog methods that don't require an ACK, the
/// {@link org.vorpal.blade.framework.v2.b2bua.Passthru Passthru} callflow uses a
/// simpler two-step pattern:
///
/// {@snippet :
/// public void process(SipServletRequest aliceRequest) throws ServletException, IOException {
///     SipSession bobSession = getLinkedSession(aliceRequest.getSession());
///     SipServletRequest bobRequest = bobSession.createRequest(request.getMethod());
///     copyContentAndHeaders(aliceRequest, bobRequest);
///
///     sendRequest(bobRequest, (bobResponse) -> {                          // Step 1: forward to Bob
///         SipServletResponse aliceResponse = createResponse(bobResponse, aliceRequest);
///         sendResponse(aliceResponse);                                    // Step 2: relay response (no ACK)
///     });
/// }
/// }
///
/// ### Terminate (BYE, CANCEL)
///
/// The {@link org.vorpal.blade.framework.v2.b2bua.Terminate Terminate} callflow
/// handles call teardown. When one party hangs up, the B2BUA must terminate the
/// other leg. The action depends on the dialog state of the linked session:
///
/// <ul>
///   <li><b>INITIAL</b> (created but not sent) &mdash; invalidate the session</li>
///   <li><b>EARLY</b> (180 Ringing received) &mdash; send CANCEL to abort the call</li>
///   <li><b>CONFIRMED</b> (200 OK received) &mdash; send BYE to end the call</li>
///   <li><b>TERMINATED</b> &mdash; nothing to do (both sides hung up simultaneously)</li>
/// </ul>
///
///
/// ## Helper Methods
///
/// [Callflow] provides static helper methods that B2BUA developers use constantly.
/// These handle the tedious work of copying SIP message content between call legs:
///
/// <table>
///   <caption>Message Creation Helpers</caption>
///   <tr><th>Method</th><th>Purpose</th></tr>
///   <tr>
///     <td>{@code createResponse(bobResponse, aliceRequest)}</td>
///     <td>Create an upstream response from a downstream response &mdash; copies
///         status code, reason phrase, headers, and content</td>
///   </tr>
///   <tr>
///     <td>{@code createAcknowlegement(bobResponse, aliceAck)}</td>
///     <td>Create a downstream ACK (or PRACK) from an upstream ACK &mdash; copies
///         content and headers</td>
///   </tr>
///   <tr>
///     <td>{@code copyContentAndHeaders(from, to)}</td>
///     <td>Copy all non-system headers and the message body between any two SIP
///         messages &mdash; also automatically links sessions for INVITE/ACK</td>
///   </tr>
///   <tr>
///     <td>{@code copyContent(from, to)}</td>
///     <td>Copy just the message body and content type</td>
///   </tr>
///   <tr>
///     <td>{@code copyHeaders(from, to)}</td>
///     <td>Copy just the headers (excludes system headers like Via, Call-ID, CSeq)</td>
///   </tr>
/// </table>
///
///
/// ## Session Linking
///
/// In a B2BUA, two SIP dialogs (Alice&harr;App and App&harr;Bob) share a single
/// {@code SipApplicationSession}. To navigate between them, BLADE stores a
/// {@code LINKED_SESSION} attribute on each {@code SipSession} pointing to the other:
///
/// <ul>
///   <li>{@link Callflow#linkSession(javax.servlet.sip.SipServletMessage, javax.servlet.sip.SipServletMessage) linkSession(inbound, outbound)}
///       &mdash; links two sessions bidirectionally (called automatically by
///       {@code copyContentAndHeaders()} for INVITE/ACK messages)</li>
///   <li>{@link Callflow#getLinkedSession(javax.servlet.sip.SipSession) getLinkedSession(session)}
///       &mdash; retrieves the other leg's session</li>
///   <li>{@link Callflow#unlinkSessions(javax.servlet.sip.SipSession, javax.servlet.sip.SipSession) unlinkSessions(s1, s2)}
///       &mdash; removes the link between two sessions</li>
/// </ul>
///
///
/// ## Glare Detection
///
/// On UDP transport, SIP messages can cross on the wire. If both sides send an
/// INVITE simultaneously (called "glare"), one must be rejected with 491 (Request
/// Pending). BLADE handles this automatically using three states tracked per
/// {@code SipSession}:
///
/// <table>
///   <caption>Glare States</caption>
///   <tr><th>State</th><th>Meaning</th><th>Set When</th></tr>
///   <tr>
///     <td>{@code ALLOW}</td>
///     <td>Accept incoming requests normally</td>
///     <td>ACK, CANCEL, or BYE received; final failure response sent</td>
///   </tr>
///   <tr>
///     <td>{@code PROTECT}</td>
///     <td>Reject incoming INVITE/REFER with 491</td>
///     <td>Outgoing INVITE or REFER sent</td>
///   </tr>
///   <tr>
///     <td>{@code QUEUE}</td>
///     <td>Buffer incoming requests until current exchange completes</td>
///     <td>200 OK sent for INVITE (waiting for ACK)</td>
///   </tr>
/// </table>
///
/// The framework manages these states in
/// {@link org.vorpal.blade.framework.v2.AsyncSipServlet AsyncSipServlet}'s
/// {@code doRequest()} method. Queued requests are processed automatically after
/// the current exchange completes. Developers rarely need to interact with glare
/// handling directly.
///
///
/// ## Timer Management
///
/// Callflows can schedule timers with lambda callbacks:
///
/// {@snippet :
/// // One-time timer: fire after 30 seconds
/// String timerId = startTimer(appSession, 30000, false, (timer) -> {
///     sipLogger.info("Timer expired — no response received");
///     // cleanup logic here
/// });
///
/// // Cancel the timer if no longer needed
/// stopTimer(appSession, timerId);
/// }
///
/// The callback is stored in the timer's info object and retrieved when the timer
/// fires. Timers can be one-time or periodic, and persistent (surviving server
/// restart) or transient.
///
///
/// ## Proxy Support
///
/// Callflows can proxy requests instead of acting as a B2BUA:
///
/// {@snippet :
/// Proxy proxy = request.getProxy();
/// proxy.setRecordRoute(true);
///
/// proxyRequest(proxy, destinationUri, (proxyResponse) -> {
///     sipLogger.info("Proxy response: " + proxyResponse.getStatus());
/// });
/// }
///
/// For advanced routing, use
/// {@link Callflow#proxyRequest(javax.servlet.sip.SipServletRequest, org.vorpal.blade.framework.v2.proxy.ProxyPlan, Callback) proxyRequest()}
/// with a {@link org.vorpal.blade.framework.v2.proxy.ProxyPlan ProxyPlan} for
/// multi-tier parallel/serial routing with failover.
///
///
/// ## Pre-built Callflows
///
/// The package includes several ready-to-use callflows for common scenarios:
///
/// <table>
///   <caption>Pre-built Callflows</caption>
///   <tr><th>Class</th><th>Purpose</th></tr>
///   <tr>
///     <td>[Callflow481]</td>
///     <td>Responds with 481 (Call/Transaction Does Not Exist) &mdash; used when
///         a request arrives for a dialog that has already been terminated</td>
///   </tr>
///   <tr>
///     <td>[CallflowAckBye]</td>
///     <td>Handles the CANCEL/200 race condition &mdash; when a CANCEL crosses
///         with a 200 OK on the wire, this callflow ACKs the 200 then immediately
///         sends BYE to clean up</td>
///   </tr>
///   <tr>
///     <td>[CallflowResponseCode]</td>
///     <td>Responds with a configurable status code &mdash; useful for testing
///         or for rejecting requests with specific error codes</td>
///   </tr>
///   <tr>
///     <td>[CallflowCallConnectedError]</td>
///     <td>Error recovery for connected calls &mdash; sends BYE to both legs
///         when an error occurs after the call is established</td>
///   </tr>
/// </table>
///
///
/// ## Vorpal Session and Dialog IDs
///
/// BLADE assigns compact hex identifiers to each call for logging and tracking:
///
/// <ul>
///   <li><b>Session ID</b> (8 hex digits) &mdash; identifies the
///       {@code SipApplicationSession}, shared across all dialogs in a call.
///       Generated by {@link Callflow#createVorpalSessionId} on the first INVITE.</li>
///   <li><b>Dialog ID</b> (4 hex digits) &mdash; identifies each
///       {@code SipSession} (call leg). Generated by {@link Callflow#createVorpalDialogId}.</li>
/// </ul>
///
/// These appear in every log line as {@code [sessionId:dialogId]}, making it easy
/// to trace a call across a distributed cluster. They are also carried in
/// {@code X-Vorpal-Session} and {@code X-Vorpal-ID} SIP headers for cross-system
/// correlation.
///
///
/// ## Response Classification
///
/// Static utility methods for classifying SIP responses:
///
/// <ul>
///   <li>{@link Callflow#provisional} &mdash; 1xx responses (100 Trying, 180 Ringing, etc.)</li>
///   <li>{@link Callflow#successful} &mdash; 2xx responses (200 OK, 202 Accepted, etc.)</li>
///   <li>{@link Callflow#redirection} &mdash; 3xx responses (301, 302, etc.)</li>
///   <li>{@link Callflow#failure} &mdash; 4xx, 5xx, 6xx responses</li>
/// </ul>
///
///
/// ## Core Classes
///
/// ### Base Classes
///
/// - [Callflow] - Abstract base class with sendRequest/sendResponse, session linking, timers, proxy support, and message utilities
/// - [ClientCallflow] - Base class for client-initiated callflows (process method is a no-op for requests)
/// - [Callback] - Serializable functional interface extending {@code Consumer} for SIP message callbacks
///
/// ### Pre-built Callflows
///
/// - [Callflow481] - Responds with 481 (Call/Transaction Does Not Exist)
/// - [CallflowResponseCode] - Responds with a configurable status code
/// - [CallflowAckBye] - Handles CANCEL/200 OK race conditions (ACK then BYE)
/// - [CallflowCallConnectedError] - Error cleanup for connected calls (BYE both legs)
///
/// ### Utilities
///
/// - [Expectation] - Inline callback registration for specific SIP methods without a full callflow class
///
/// @see Callflow
/// @see Callback
/// @see Expectation
/// @see ClientCallflow
/// @see org.vorpal.blade.framework.v2.AsyncSipServlet
package org.vorpal.blade.framework.v2.callflow;
