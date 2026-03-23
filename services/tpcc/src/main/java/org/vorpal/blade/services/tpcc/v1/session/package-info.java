/// # Session Management API
///
/// This package provides REST API data transfer objects for managing TPCC (Third Party Call Control) 
/// sessions in the Vorpal Blade SIP application framework.
///
/// ## Overview
///
/// The session management API handles the creation and retrieval of SIP application sessions,
/// including their associated attributes, groups, and dialogs. Sessions can be configured with
/// expiration times and invalidation policies to control their lifecycle within the SIP container.
///
/// ## Key Classes
///
/// - [SessionCreateRequest] - Request object for creating new sessions with configurable 
///   expiration, invalidation behavior, groups, and attributes
/// - [SessionGetResponse] - Response object containing complete session state including 
///   groups, attributes, and associated dialogs with methods for building and manipulating
///   session data
///
/// ## Features
///
/// The session management system supports:
/// 
/// - **Session Expiration Control** - Configure automatic session timeout behavior
/// - **Group-based Organization** - Organize sessions into logical groups for management
/// - **Custom Attributes** - Store arbitrary key-value pairs with sessions
/// - **Dialog Association** - Manage SIP dialogs associated with application sessions
/// - **SIP Integration** - Direct integration with `SipApplicationSession` objects
/// - **Invalidation Policies** - Control when sessions are automatically invalidated
///
/// ## Request/Response Model
///
/// The API follows a standard request/response pattern where [SessionCreateRequest] objects
/// define the desired session configuration, and [SessionGetResponse] objects provide complete
/// session state information including all associated dialogs, groups, and attributes.
///
/// @see [org.vorpal.blade.services.tpcc.v1.dialog.Dialog]
/// @see javax.servlet.sip.SipApplicationSession
/// @see javax.servlet.sip.SipSession
package org.vorpal.blade.services.tpcc.v1.session;
