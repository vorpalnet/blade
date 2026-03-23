/// # B2BUA Framework Package
///
/// This package provides a comprehensive framework for implementing Back-to-Back User Agent (B2BUA) 
/// functionality in SIP servlet applications. A B2BUA acts as an intermediary that terminates incoming 
/// SIP calls and creates corresponding outbound calls, enabling call routing, modification, and control.
///
/// ## Core Components
///
/// ### Main Classes
///
/// - [B2buaServlet] - Abstract base servlet that implements the core B2BUA logic and extends AsyncSipServlet
/// - [B2buaListener] - Callback interface for handling B2BUA call lifecycle events and message modification
/// - [B2buaConfiguration] - Configuration class extending the base Configuration for B2BUA-specific settings
///
/// ### Callflow Handlers
///
/// The package includes specialized callflow classes for handling different SIP message types:
///
/// - [InitialInvite] - Handles initial INVITE requests, creates outbound leg and links sessions
/// - [Reinvite] - Processes re-INVITE requests for mid-call modifications with SDP exchange
/// - [Terminate] - Unified handler for BYE and CANCEL requests to terminate both call legs
/// - [Passthru] - Forwards mid-dialog requests (INFO, OPTIONS, etc.) between call legs
///
/// ### Legacy Components
///
/// Several deprecated classes are maintained for backward compatibility:
///
/// - [Bye] - Deprecated BYE handler, replaced by [Terminate]
/// - [Cancel] - Deprecated CANCEL handler, replaced by [Terminate]
/// - [ByeOld] - Legacy BYE implementation with complex glare handling
/// - [CancelOld] - Legacy CANCEL implementation with detailed sequence documentation
///
/// ## Architecture
///
/// The B2BUA framework follows a callflow-based architecture where different SIP message types are 
/// handled by specific callflow classes. The [B2buaServlet] serves as the main entry point, routing 
/// requests to appropriate callflows and providing lifecycle callbacks through the [B2buaListener] interface.
///
/// Applications extend [B2buaServlet] and implement the [B2buaListener] methods to customize call handling,
/// modify messages, and implement routing logic. The framework automatically manages session linking,
/// message forwarding, and call state transitions.
///
/// ## Call Lifecycle Events
///
/// The [B2buaListener] interface provides callback methods for key call lifecycle events:
///
/// - `callStarted()` - Called when an outbound call is initiated
/// - `callAnswered()` - Called when the outbound call receives a success response
/// - `callConnected()` - Called when the call is fully established
/// - `callCompleted()` - Called when the call terminates normally
/// - `callDeclined()` - Called when the outbound call is rejected
/// - `callAbandoned()` - Called when the call is cancelled or abandoned
/// - `requestEvent()` - Called for general request processing
/// - `responseEvent()` - Called for general response processing
///
/// @see org.vorpal.blade.framework.v2.AsyncSipServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.framework.v2.config.Configuration
package org.vorpal.blade.framework.v2.b2bua;
