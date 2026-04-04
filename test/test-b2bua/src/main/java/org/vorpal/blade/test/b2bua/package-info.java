/// A sample B2BUA application that serves as both a functional test and a reference
/// implementation. This is the template that most BLADE developers copy when starting
/// a new B2BUA project.
///
///
/// ## Purpose
///
/// This application doesn't implement any real business logic&mdash;it simply passes
/// calls through as a transparent B2BUA while logging every lifecycle event. Its value
/// is threefold:
///
/// <ol>
///   <li><b>Verification</b>&mdash;confirms that the B2BUA framework is working
///       correctly on a given OCCAS deployment</li>
///   <li><b>Template</b>&mdash;demonstrates the exact annotations, initialization
///       pattern, and callback structure that all B2BUA applications follow</li>
///   <li><b>Debugging aid</b>&mdash;the lifecycle logging makes it easy to trace
///       call flow and diagnose issues</li>
/// </ol>
///
///
/// ## How to Use This as a Template
///
/// To create a new B2BUA application:
///
/// <ol>
///   <li>Copy this module's directory structure</li>
///   <li>Rename {@link SampleB2buaServlet} to your application name</li>
///   <li>Replace {@link SampleB2buaConfig} with your own configuration class
///       (add the properties your application needs)</li>
///   <li>Replace {@link ConfigSample} with default values for your config</li>
///   <li>Add your business logic to the lifecycle callbacks</li>
/// </ol>
///
///
/// ## The Servlet
///
/// {@link SampleB2buaServlet} demonstrates the standard annotation pattern:
///
/// {@snippet :
/// @WebListener
/// @SipApplication(distributable = true)
/// @SipServlet(loadOnStartup = 1)
/// @SipListener
/// public class SampleB2buaServlet extends B2buaServlet {
///     // ...
/// }
/// }
///
/// <ul>
///   <li>{@code @WebListener} &mdash; receives servlet context lifecycle events</li>
///   <li>{@code @SipApplication(distributable = true)} &mdash; enables cluster
///       replication of SIP sessions (required for failover)</li>
///   <li>{@code @SipServlet(loadOnStartup = 1)} &mdash; initializes immediately
///       on deployment</li>
///   <li>{@code @SipListener} &mdash; receives SIP session lifecycle events</li>
/// </ul>
///
/// ### Initialization
///
/// The {@code servletCreated()} method creates a
/// {@link org.vorpal.blade.framework.v2.config.SettingsManager SettingsManager} with
/// the application's config class and default values:
///
/// {@snippet :
/// public void servletCreated(SipServletContextEvent event) {
///     new SettingsManager<>(event, SampleB2buaConfig.class, new ConfigSample());
/// }
/// }
///
/// This single line handles loading config files from the three-tier hierarchy,
/// generating a JSON Schema, registering a JMX MBean for runtime reload, and
/// initializing the logging system.
///
/// ### Lifecycle Callbacks
///
/// All eight {@link org.vorpal.blade.framework.v2.b2bua.B2buaListener B2buaListener}
/// callbacks are implemented with logging. In a real application, you would add
/// your business logic here&mdash;routing decisions in {@code callStarted()},
/// CDR logging in {@code callAnswered()} and {@code callCompleted()}, etc.
///
///
/// ## Configuration
///
/// {@link SampleB2buaConfig} extends
/// {@link org.vorpal.blade.framework.v2.config.Configuration Configuration} and adds
/// two custom string properties ({@code value1}, {@code value2}) as a demonstration.
/// In a real application, replace these with your own configuration properties.
///
/// {@link ConfigSample} provides the default values, including
/// {@link org.vorpal.blade.framework.v2.logging.LogParametersDefault LogParametersDefault}
/// and {@link org.vorpal.blade.framework.v2.config.SessionParametersDefault SessionParametersDefault}.
///
///
/// ## CANCEL Glare Testing
///
/// {@link CancelGlare} is a specialized callflow that deliberately suppresses CANCEL
/// messages (returning 200 OK instead of forwarding). This is used to test the
/// framework's handling of the race condition where a CANCEL and a 200 OK cross
/// on the wire&mdash;a scenario that the
/// {@link org.vorpal.blade.framework.v2.callflow.CallflowAckBye CallflowAckBye}
/// pre-built callflow is designed to handle.
///
///
/// @see SampleB2buaServlet
/// @see SampleB2buaConfig
/// @see ConfigSample
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
package org.vorpal.blade.test.b2bua;
