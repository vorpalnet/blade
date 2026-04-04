/// # TPCC Dialog Services v1
///
/// This package provides data transfer objects and request/response models for managing
/// SIP dialogs in the TPCC (Third Party Call Control) service version 1. It contains
/// classes that represent dialog states, connection requests, and property management
/// for SIP-based telephony applications.
///
/// ## Core Classes
///
/// - [Dialog] - Represents a complete SIP dialog with request URI, parties, and attributes
/// - [DialogRequest] - Request model for creating new dialogs with callback support
/// - [DialogResponse] - Response model containing dialog attributes after operations
/// - [DialogConnectRequest] - Request model for connecting two existing dialogs
/// - [DialogProperties] - Read-only view of dialog properties including remote party and attributes
/// - [DialogPutAttributes] - Request model for updating dialog attributes
///
/// ## Usage Context
///
/// These classes work with [javax.servlet.sip.SipSession] objects to provide a REST-like
/// interface for SIP dialog management. They serve as the data layer between HTTP API
/// endpoints and the underlying SIP servlet container, enabling third-party call control
/// operations through structured data objects.
///
/// All dialog classes include attribute maps for storing custom key-value pairs associated
/// with SIP sessions, allowing for flexible session state management.
///
/// @see Dialog
/// @see DialogRequest
/// @see DialogConnectRequest
/// @see javax.servlet.sip.SipSession
package org.vorpal.blade.services.tpcc.v1.dialog;
