/// Create, Read, Update & Delete (CRUD), a rule-driven SIP message transformation service
/// for manipulating headers and body content.
///
/// ## What It Does
///
/// The CRUD service is a configurable B2BUA that applies transformation rules to SIP
/// messages at every point in the call lifecycle. Without writing any Java code, you can:
///
/// <ul>
///   <li><b>Read</b> values from SIP headers, Request-URI, or message bodies using
///       regex patterns, and store them as session attributes</li>
///   <li><b>Create</b> new headers or body content, with {@code ${variable}}
///       substitution from previously read values</li>
///   <li><b>Update</b> existing headers or body content using regex find-and-replace</li>
///   <li><b>Delete</b> headers or body content</li>
/// </ul>
///
/// These operations work on four content types:
///
/// <table>
///   <caption>Operation Types</caption>
///   <tr><th>Content</th><th>Addressing</th><th>Operations</th></tr>
///   <tr>
///     <td>SIP headers and Request-URI</td>
///     <td>Regex with named capturing groups</td>
///     <td>{@code read}, {@code create}, {@code update}, {@code delete}</td>
///   </tr>
///   <tr>
///     <td>XML bodies</td>
///     <td>XPath expressions</td>
///     <td>{@code xpathRead}, {@code xpathCreate}, {@code xpathUpdate}, {@code xpathDelete}</td>
///   </tr>
///   <tr>
///     <td>JSON bodies</td>
///     <td>JsonPath expressions</td>
///     <td>{@code jsonPathRead}, {@code jsonPathCreate}, {@code jsonPathUpdate}, {@code jsonPathDelete}</td>
///   </tr>
///   <tr>
///     <td>SDP bodies</td>
///     <td>JsonPath on SDP-to-JSON conversion</td>
///     <td>{@code sdpRead}, {@code sdpCreate}, {@code sdpUpdate}, {@code sdpDelete}</td>
///   </tr>
/// </table>
///
///
/// ## How It Works
///
/// <ol>
///   <li>An incoming call is matched to a {@link RuleSet} via the standard BLADE
///       routing engine (selectors, translation maps, and routing plan from
///       {@link org.vorpal.blade.framework.v2.config.RouterConfig RouterConfig})</li>
///   <li>At each B2BUA lifecycle event ({@code callStarted}, {@code callAnswered},
///       {@code callConnected}, etc.), the framework iterates through the rules in
///       the matched {@link RuleSet}</li>
///   <li>Each {@link Rule} checks its filters (method, message type, event) to decide
///       whether to fire</li>
///   <li>Matching rules execute their operations in order: all reads first, then
///       creates, then updates, then deletes</li>
/// </ol>
///
///
/// ## Configuration Example
///
/// The following JSON configuration extracts the caller's username from the From
/// header on initial INVITE, adds a custom header with that value, and strips a
/// private header from all outbound requests:
///
/// <pre>{@code
/// {
///   "selectors": [
///     {
///       "id": "dialed-number",
///       "description": "Extract dialed number from To header",
///       "attribute": "To",
///       "pattern": "^(?:\"?(?<name>.*?)\"?\\s*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*$",
///       "expression": "${user}"
///     }
///   ],
///   "ruleSets": {
///     "sample-rules": {
///       "id": "sample-rules",
///       "rules": [
///         {
///           "id": "read-caller",
///           "description": "Extract caller user and host from From header",
///           "method": "INVITE",
///           "event": "callStarted",
///           "read": [
///             { "attribute": "From", "pattern": "sip:(?<user>[^@]+)@(?<host>[^;>]+)" }
///           ]
///         },
///         {
///           "id": "add-header",
///           "description": "Add X-Caller-Info using extracted values",
///           "method": "INVITE",
///           "event": "callStarted",
///           "create": [
///             { "attribute": "X-Caller-Info", "value": "${user}@${host}" }
///           ]
///         },
///         {
///           "id": "strip-private",
///           "description": "Remove X-Private-Data from all requests",
///           "messageType": "request",
///           "delete": [
///             { "attribute": "X-Private-Data" }
///           ]
///         }
///       ]
///     }
///   },
///   "maps": [
///     {
///       "type": "hash",
///       "id": "dialed-number-map",
///       "selectors": ["dialed-number"],
///       "map": {
///         "alice": { "id": "route-alice", "attributes": { "ruleSet": "sample-rules" } }
///       }
///     }
///   ],
///   "plan": ["dialed-number-map"],
///   "defaultRoute": { "id": "default", "attributes": { "ruleSet": "sample-rules" } }
/// }
/// }</pre>
///
///
/// ## Rules and Filters
///
/// Each {@link Rule} has three optional filter fields. A {@code null} filter acts as
/// a wildcard (matches everything):
///
/// <table>
///   <caption>Rule Filters</caption>
///   <tr><th>Filter</th><th>Values</th><th>Example</th></tr>
///   <tr>
///     <td>{@code method}</td>
///     <td>Any SIP method: INVITE, BYE, INFO, etc.</td>
///     <td>{@code "method": "INVITE"}</td>
///   </tr>
///   <tr>
///     <td>{@code messageType}</td>
///     <td>{@code "request"} or {@code "response"}</td>
///     <td>{@code "messageType": "request"}</td>
///   </tr>
///   <tr>
///     <td>{@code event}</td>
///     <td>B2BUA lifecycle event: {@code callStarted}, {@code callAnswered},
///         {@code callConnected}, {@code callCompleted}, {@code callDeclined},
///         {@code callAbandoned}, {@code requestEvent}, {@code responseEvent}</td>
///     <td>{@code "event": "callStarted"}</td>
///   </tr>
/// </table>
///
///
/// ## Operations in Detail
///
/// ### Read (Regex)
///
/// Extracts named capturing groups from a SIP header (or body, Request-URI, status,
/// reason, remoteIP) and stores them as {@code SipApplicationSession} attributes:
///
/// <pre>{@code
/// { "attribute": "From", "pattern": "sip:(?<user>[^@]+)@(?<host>[^;>]+)" }
/// }</pre>
///
/// After this rule fires, {@code ${user}} and {@code ${host}} are available for
/// substitution in subsequent create and update operations.
///
/// ### Create (Regex)
///
/// Adds a new header or sets the message body, with {@code ${variable}} substitution:
///
/// <pre>{@code
/// { "attribute": "X-Caller-Info", "value": "${user}@${host}" }
/// }</pre>
///
/// ### Update (Regex)
///
/// Modifies an existing header or body using regex find-and-replace:
///
/// <pre>{@code
/// { "attribute": "From", "pattern": "sip:(.*)@(.*)", "replacement": "sip:anonymous@${host}" }
/// }</pre>
///
/// ### Delete (Regex)
///
/// Removes a header entirely, or clears the message body:
///
/// <pre>{@code
/// { "attribute": "X-Private-Data" }
/// }</pre>
///
/// ### XPath Operations (XML bodies)
///
/// For messages with XML bodies, XPath expressions address specific elements:
///
/// <pre>{@code
/// "xpathRead":   [{ "expressions": { "greeting": "//message/greeting/text()" } }]
/// "xpathUpdate": [{ "xpath": "//message/greeting", "value": "Hello ${user}" }]
/// "xpathCreate": [{ "parentXpath": "//message", "elementName": "timestamp", "value": "2024-01-01" }]
/// "xpathDelete": [{ "xpath": "//message/debug" }]
/// }</pre>
///
/// ### JsonPath Operations (JSON bodies)
///
/// For messages with JSON bodies, JsonPath expressions address properties:
///
/// <pre>{@code
/// "jsonPathRead":   [{ "expressions": { "caller": "$.caller.name" } }]
/// "jsonPathUpdate": [{ "jsonPath": "$.caller.name", "value": "${user}" }]
/// "jsonPathCreate": [{ "parentPath": "$.caller", "key": "department", "value": "sales" }]
/// "jsonPathDelete": [{ "jsonPath": "$.caller.debug" }]
/// }</pre>
///
/// ### SDP Operations
///
/// SDP bodies are internally converted to JSON, manipulated via JsonPath, and
/// converted back. The JSON structure mirrors the SDP format:
///
/// <pre>{@code
/// "sdpRead":   [{ "expressions": { "mediaPort": "$.media[0].port" } }]
/// "sdpUpdate": [{ "jsonPath": "$.connection.address", "value": "10.0.0.1" }]
/// }</pre>
///
/// Common SDP JsonPath expressions:
/// <ul>
///   <li>{@code $.connection.address} &mdash; session-level connection address</li>
///   <li>{@code $.media[0].port} &mdash; first media stream port</li>
///   <li>{@code $.media[0].attributes[?(@.name=='rtpmap')].value} &mdash; RTP map attribute</li>
///   <li>{@code $.origin.address} &mdash; origin address</li>
/// </ul>
///
///
/// ## Multipart Body Support
///
/// For multipart SIP messages (e.g. INVITE with both SDP and XML), specify the
/// {@code contentType} field on any operation to target a specific MIME part:
///
/// <pre>{@code
/// { "attribute": "body", "pattern": "...", "contentType": "application/sdp" }
/// }</pre>
///
/// The {@link MimeHelper} utility parses multipart boundaries, targets the matching
/// part, and reassembles the multipart body after modification.
///
///
/// ## Execution Order
///
/// Within a single rule, operations execute in this order:
///
/// <ol>
///   <li>Regex read &rarr; XPath read &rarr; JsonPath read &rarr; SDP read</li>
///   <li>Regex create &rarr; XPath create &rarr; JsonPath create &rarr; SDP create</li>
///   <li>Regex update &rarr; XPath update &rarr; JsonPath update &rarr; SDP update</li>
///   <li>Regex delete &rarr; XPath delete &rarr; JsonPath delete &rarr; SDP delete</li>
/// </ol>
///
/// This ensures reads populate session variables before creates and updates use them.
///
///
/// ## Core Classes
///
/// ### Servlet and Configuration
///
/// - {@link CrudServlet} - B2BUA servlet that applies rules at each lifecycle event
/// - {@link CrudConfiguration} - Extends RouterConfig with a map of named RuleSets
/// - {@link CrudConfigurationSample} - Sample configuration demonstrating all operations
///
/// ### Rule Engine
///
/// - {@link RuleSet} - Named collection of rules applied to matched calls
/// - {@link Rule} - Filters (method, messageType, event) plus ordered operation lists
///
/// ### Regex Operations (SIP Headers)
///
/// - {@link ReadOperation} - Extract named groups into session attributes
/// - {@link CreateOperation} - Add headers or body with variable substitution
/// - {@link UpdateOperation} - Regex find-and-replace on headers or body
/// - {@link DeleteOperation} - Remove headers or clear body
///
/// ### XPath Operations (XML Bodies)
///
/// - {@link XPathReadOperation} - Extract values via XPath into session attributes
/// - {@link XPathCreateOperation} - Add elements or attributes to XML
/// - {@link XPathUpdateOperation} - Modify XML node values
/// - {@link XPathDeleteOperation} - Remove XML nodes
///
/// ### JsonPath Operations (JSON Bodies)
///
/// - {@link JsonPathReadOperation} - Extract values via JsonPath into session attributes
/// - {@link JsonPathCreateOperation} - Add properties to JSON objects
/// - {@link JsonPathUpdateOperation} - Modify JSON values
/// - {@link JsonPathDeleteOperation} - Remove JSON properties
///
/// ### SDP Operations
///
/// - {@link SdpReadOperation} - Extract SDP values via SDP-to-JSON conversion
/// - {@link SdpCreateOperation} - Add SDP attributes or media
/// - {@link SdpUpdateOperation} - Modify SDP values
/// - {@link SdpDeleteOperation} - Remove SDP media lines or attributes
///
/// ### Helpers
///
/// - {@link MessageHelper} - Reads/writes SIP message attributes by name
/// - {@link XmlHelper} - DOM-based XML parsing, XPath evaluation, and manipulation
/// - {@link SdpHelper} - Bidirectional SDP-to-JSON conversion
/// - {@link MimeHelper} - Multipart MIME body parsing and reassembly
///
/// @see CrudServlet
/// @see CrudConfiguration
/// @see RuleSet
/// @see Rule
package org.vorpal.blade.services.crud;
