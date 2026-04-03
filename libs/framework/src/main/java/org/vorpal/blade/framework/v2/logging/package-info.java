/// The logging package provides SIP-aware logging with automatic session tracking,
/// ASCII sequence diagram generation, and independent logging levels for different
/// subsystems.
///
/// ## Quick Start
///
/// Every BLADE application inherits logging automatically. The framework creates a
/// per-application [Logger] during servlet initialization&mdash;no setup code required.
/// Inside any [org.vorpal.blade.framework.v2.callflow.Callflow Callflow], the logger is
/// available as the inherited {@code sipLogger} field:
///
/// {@snippet :
/// sipLogger.info(sipMessage, "Processing new call");
/// sipLogger.fine(sipSession, "Session state updated");
/// sipLogger.warning(appSession, "Timeout approaching");
/// }
///
/// Each log statement automatically prefixes the output with a session identifier
/// in the format {@code [sessionId:dialogId] appName}, making it easy to correlate
/// log entries across a distributed cluster.
///
///
/// ## Configuration
///
/// Logging behavior is controlled by the {@code "logging"} section of each application's
/// JSON configuration file. The [LogParameters] class maps directly to this JSON structure.
/// Here is an example with all available properties:
///
/// <pre>{@code
/// "logging": {
///     "useParentLogging": false,
///     "directory": "./servers/${weblogic.Name}/logs/vorpal",
///     "fileName": "${sip.application.name}.%g.log",
///     "fileSize": "100MiB",
///     "fileCount": 25,
///     "appendFile": true,
///     "loggingLevel": "INFO",
///     "sequenceDiagramLoggingLevel": "INFO",
///     "configurationLoggingLevel": "FINER",
///     "analyticsLoggingLevel": "FINER",
///     "colorsEnabled": false
/// }
/// }</pre>
///
/// If the {@code "logging"} section is omitted entirely, [LogParametersDefault] supplies
/// sensible production defaults.
///
/// ### Variable Substitution
///
/// The {@code directory} and {@code fileName} fields support {@code ${variable.name}} syntax.
/// Variables are resolved in this order:
///
/// <ol>
///   <li>Environment variables ({@code System.getenv()})</li>
///   <li>Java system properties ({@code System.getProperties()})</li>
///   <li>Servlet context init parameters</li>
///   <li>Servlet context attributes</li>
/// </ol>
///
/// The two most commonly used variables are:
/// <ul>
///   <li>{@code ${weblogic.Name}}&mdash;the name of the WebLogic managed server instance</li>
///   <li>{@code ${sip.application.name}}&mdash;the SIP application name from {@code sip.xml}</li>
/// </ul>
///
/// With the defaults, each application writes to its own rotating log file under the
/// server's log directory, e.g.
/// {@code ./servers/ms1/logs/vorpal/proxy-registrar.0.log}.
///
/// ### File Rotation
///
/// The {@code fileSize} field accepts human-readable units: {@code KB}, {@code KiB},
/// {@code MB}, {@code MiB}, {@code GB}, {@code GiB}. When a log file reaches the
/// configured size, it rotates. The {@code %g} token in the filename is replaced by
/// a generation number (0 through {@code fileCount - 1}). Setting {@code appendFile}
/// to {@code true} (the default) preserves log files across server restarts.
///
/// ### Parent Logging
///
/// When {@code useParentLogging} is {@code false} (the default), each BLADE application
/// writes to its own dedicated log file. When {@code true}, log output is directed to the
/// WebLogic engine's parent logger instead, and the {@code directory}, {@code fileName},
/// and rotation settings are ignored.
///
///
/// ## Logging Levels
///
/// BLADE uses four independent logging level controls, each mapped to the standard
/// {@link java.util.logging.Level} hierarchy. Available values, from least to most verbose:
/// {@code OFF}, {@code SEVERE}, {@code WARNING}, {@code INFO}, {@code CONFIG},
/// {@code FINE}, {@code FINER}, {@code FINEST}, {@code ALL}.
///
/// <table>
///   <caption>Logging Level Controls</caption>
///   <tr><th>Property</th><th>Default</th><th>Controls</th></tr>
///   <tr>
///     <td>{@code loggingLevel}</td>
///     <td>{@code INFO}</td>
///     <td>General application log statements ({@code sipLogger.info(...)},
///         {@code sipLogger.fine(...)}, etc.)</td>
///   </tr>
///   <tr>
///     <td>{@code sequenceDiagramLoggingLevel}</td>
///     <td>{@code FINE}</td>
///     <td>ASCII sequence diagram arrows showing SIP message flow
///         (see <a href="#sequence-diagrams">Sequence Diagrams</a> below)</td>
///   </tr>
///   <tr>
///     <td>{@code configurationLoggingLevel}</td>
///     <td>{@code INFO}</td>
///     <td>JSON dumps of configuration objects at startup and reload, via
///         {@link Logger#logConfiguration(Object)}</td>
///   </tr>
///   <tr>
///     <td>{@code analyticsLoggingLevel}</td>
///     <td>{@code INFO}</td>
///     <td>Analytics events emitted by
///         {@link Logger#logEvent(javax.servlet.sip.SipSession, org.vorpal.blade.framework.v2.analytics.Event)}</td>
///   </tr>
/// </table>
///
/// Because the four levels are independent, you can combine them freely. For example,
/// setting {@code loggingLevel} to {@code WARNING} silences routine application messages
/// while keeping {@code sequenceDiagramLoggingLevel} at {@code INFO} to continue
/// capturing call flow diagrams.
///
/// All four levels write to the same log file. The distinction is not
/// <em>where</em> output goes, but <em>which categories of output</em> are enabled.
///
///
/// <h2 id="sequence-diagrams">Sequence Diagrams</h2>
///
/// The sequence diagram feature is BLADE's most distinctive logging capability. When
/// enabled, every SIP request and response flowing through the application is rendered as
/// an ASCII arrow in the log file, producing a visual call flow that reads like a UML
/// sequence diagram.
///
/// ### How It Works
///
/// The framework automatically generates diagram entries at two points:
/// <ul>
///   <li><b>Receiving</b>&mdash;when {@link org.vorpal.blade.framework.v2.AsyncSipServlet AsyncSipServlet}
///       dispatches an incoming request or response to a callflow</li>
///   <li><b>Sending</b>&mdash;when a callflow calls
///       {@code Callflow.sendRequest()} or {@code Callflow.sendResponse()}</li>
/// </ul>
///
/// No application code is needed&mdash;the diagrams appear automatically as long as the
/// {@code sequenceDiagramLoggingLevel} is enabled.
///
/// ### Reading the Diagram
///
/// Each arrow line has this structure:
///
/// <pre>
/// [sessionId:dialogId] appName [left party]----METHOD or STATUS---->[right party] ; note
/// </pre>
///
/// <ul>
///   <li><b>{@code [sessionId:dialogId]}</b>&mdash;an 8-character hex hash of the
///       {@code SipApplicationSession} and a 4-character hex hash of the {@code SipSession},
///       used to correlate all messages belonging to the same call leg</li>
///   <li><b>{@code appName}</b>&mdash;the application name, so entries from different
///       BLADE applications can be distinguished in a merged log view</li>
///   <li><b>Arrow direction</b>&mdash;{@code --->} indicates the message is flowing
///       left-to-right; {@code <---} indicates right-to-left</li>
///   <li><b>Party names</b>&mdash;the SIP user or host extracted from the From/To headers</li>
///   <li><b>Note</b>&mdash;contextual detail after the semicolon, varies by method:
///       INVITE shows the Request-URI or To header, REFER shows Refer-To,
///       NOTIFY shows Event and Subscription-State, REGISTER shows Expires</li>
/// </ul>
///
/// ### Example: B2BUA Call with Analytics
///
/// The following is actual log output from the transfer application. Alice calls Bob
/// through a B2BUA; Bob eventually hangs up:
///
/// <pre>
/// INFO 2026-04-03 04:20:32.751 - [621EA3E1:760D] transfer =======================================================================================
/// INFO 2026-04-03 04:20:32.752 - [621EA3E1:760D] transfer [alice]------------INVITE-(sdp)--&gt;[TransferInitial]                                   ; sip:bob@vorpal.net
/// INFO 2026-04-03 04:20:32.754 - [621EA3E1:F980] transfer event=callStarted, caller=alice, callee=bob, requestUri=sip:bob@vorpal.net, ani=alice, did=bob
/// INFO 2026-04-03 04:20:32.758 - [621EA3E1:F980] transfer                                   [TransferInitial]--INVITE-(sdp)--&gt;[bob]             ; sip:bob@vorpal.net
/// INFO 2026-04-03 04:20:35.119 - [621EA3E1:F980] transfer                                   [InitialInvite]&lt;------------180---[bob]             ; Ringing (INVITE)
/// INFO 2026-04-03 04:20:35.120 - [621EA3E1:760D] transfer [alice]&lt;--------------------180---[TransferInitial]                                   ; Ringing (INVITE)
/// INFO 2026-04-03 04:20:39.049 - [621EA3E1:F980] transfer                                   [InitialInvite]&lt;------200-(sdp)---[bob]             ; OK (INVITE)
/// INFO 2026-04-03 04:20:39.050 - [621EA3E1:760D] transfer event=callAnswered
/// INFO 2026-04-03 04:20:39.051 - [621EA3E1:760D] transfer [alice]&lt;--------------200-(sdp)---[TransferInitial]                                   ; OK (INVITE)
/// INFO 2026-04-03 04:20:39.111 - [621EA3E1:760D] transfer [alice]---------------------ACK--&gt;[InitialInvite]                                     ;
/// INFO 2026-04-03 04:20:39.111 - [621EA3E1:F980] transfer event=callConnected
/// INFO 2026-04-03 04:20:39.113 - [621EA3E1:F980] transfer                                   [TransferInitial]-----------ACK--&gt;[bob]             ;
/// INFO 2026-04-03 04:20:49.001 - [621EA3E1:F980] transfer                                   [Terminate]&lt;----------------BYE---[bob]             ;
/// INFO 2026-04-03 04:20:49.002 - [621EA3E1:760D] transfer event=callCompleted, disconnector=bob
/// INFO 2026-04-03 04:20:49.003 - [621EA3E1:760D] transfer [alice]&lt;--------------------BYE---[Terminate]                                         ;
/// INFO 2026-04-03 04:20:49.003 - [621EA3E1:F980] transfer                                   [Terminate]-----------------200--&gt;[bob]             ; OK (BYE)
/// INFO 2026-04-03 04:20:49.190 - [621EA3E1:760D] transfer [alice]---------------------200--&gt;[TransferServlet]                                   ; OK (BYE)
/// </pre>
///
/// Key things to notice:
///
/// <ul>
///   <li>The {@code ====} separator line marks the start of a new call, making it easy
///       to find call boundaries in a busy log file.</li>
///   <li>The session ID {@code 621EA3E1} is shared across all lines, tying every message
///       to the same {@code SipApplicationSession}. The dialog IDs ({@code 760D} for
///       Alice's leg, {@code F980} for Bob's leg) distinguish the two SIP dialogs
///       within the B2BUA.</li>
///   <li>Alice's messages appear on the left side; Bob's appear indented on the right.
///       This two-column layout makes the B2BUA's role as a middleman visually obvious.</li>
///   <li>The callflow class name changes as the call progresses: {@code TransferInitial}
///       handles setup, {@code InitialInvite} handles mid-dialog messages, and
///       {@code Terminate} handles teardown. This shows which callflow lambda is
///       executing at each step.</li>
///   <li>Analytics events ({@code event=callStarted}, {@code event=callAnswered}, etc.)
///       are interleaved with the diagram arrows, providing a unified view of both
///       the SIP signaling and the business-level call lifecycle.</li>
///   <li>The {@code (sdp)} suffix on INVITE and 200 OK indicates that those messages
///       carry a Session Description Protocol body (the media offer/answer).</li>
/// </ul>
///
/// ### Controlling Diagram Verbosity
///
/// The {@code sequenceDiagramLoggingLevel} determines the minimum level at which diagrams
/// are emitted. Additionally, if the main {@code loggingLevel} is set to {@code FINEST},
/// the full raw SIP message text is logged immediately after each arrow, providing
/// complete protocol-level detail for deep debugging.
///
/// Typical configurations:
/// <ul>
///   <li><b>Production monitoring</b>&mdash;set {@code sequenceDiagramLoggingLevel} to
///       {@code INFO} to see call flows alongside normal application logs</li>
///   <li><b>Development/debugging</b>&mdash;set {@code sequenceDiagramLoggingLevel} to
///       {@code FINE} (the default) and {@code loggingLevel} to {@code FINE} or
///       {@code FINER} to see diagrams plus detailed application-level trace</li>
///   <li><b>Full protocol trace</b>&mdash;set {@code loggingLevel} to {@code FINEST}
///       to see raw SIP message dumps after each arrow</li>
///   <li><b>Disabled</b>&mdash;set {@code sequenceDiagramLoggingLevel} to {@code OFF}
///       to suppress diagrams entirely</li>
/// </ul>
///
///
/// ## Session-Aware Logging
///
/// Every logging method on [Logger] has overloads that accept SIP context objects:
/// {@code SipServletMessage}, {@code SipSession}, {@code SipApplicationSession},
/// {@code ServletTimer}, or {@code Proxy}. When you pass one of these, the logger
/// automatically extracts session and dialog identifiers and prepends them to the
/// message:
///
/// {@snippet :
/// // Without context — you see: "INFO 2025-06-15 10:30:00.123 - Call processed"
/// sipLogger.info("Call processed");
///
/// // With context — you see: "INFO 2025-06-15 10:30:00.123 - [a1b2c3d4:e5f6] myapp Call processed"
/// sipLogger.info(sipMessage, "Call processed");
/// }
///
/// This is essential in production where hundreds of calls are in progress simultaneously.
/// The session prefix lets you filter a log file down to a single call by searching for
/// its session ID.
///
///
/// ## Configuration Logging
///
/// The {@link Logger#logConfiguration(Object)} method serializes any configuration object
/// to JSON and logs it at the {@code configurationLoggingLevel}. The framework calls this
/// automatically at startup and whenever configuration is reloaded. Set the level to
/// {@code FINER} or {@code FINEST} to capture full configuration snapshots in the log,
/// or to {@code OFF} to suppress them.
///
///
/// ## Analytics Logging
///
/// When the analytics subsystem is enabled, events are logged via
/// {@link Logger#logEvent(javax.servlet.sip.SipSession, org.vorpal.blade.framework.v2.analytics.Event)}.
/// The {@code analyticsLoggingLevel} controls the verbosity of these entries independently
/// from other log output.
///
///
/// ## ANSI Color Support
///
/// Setting {@code colorsEnabled} to {@code true} activates ANSI color codes in log output.
/// The [Color] utility class provides color methods that automatically check this flag
/// before applying formatting. Colors are useful when tailing logs in a terminal during
/// development but should be disabled in production where log files are typically viewed
/// in plain-text tools or log aggregation systems.
///
///
/// ## Core Components
///
/// - [Logger] - Extended {@link java.util.logging.Logger} with SIP session context,
///   sequence diagram generation, JSON serialization, and analytics event logging
/// - [LogManager] - Factory and lifecycle manager for Logger instances, integrates with
///   WebLogic's {@code KernelLogManager}
/// - [LogParameters] - Configuration POJO mapping directly to the JSON {@code "logging"} section
/// - [LogParametersDefault] - Default values used when configuration omits the logging section
/// - [LogFormatter] - Formats log records as {@code LEVEL YYYY-MM-DD HH:mm:ss.SSS - message}
/// - [Color] - ANSI color code utility with configuration-aware wrapper methods
///
/// @see Logger
/// @see LogManager
/// @see LogParameters
/// @see LogParametersDefault
/// @see LogFormatter
/// @see Color
package org.vorpal.blade.framework.v2.logging;
