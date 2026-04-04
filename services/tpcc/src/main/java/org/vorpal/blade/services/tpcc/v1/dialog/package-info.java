/// This package provides data transfer objects and request/response models for managing
/// SIP dialogs in the TPCC (Third Party Call Control) service version 1. It contains
/// classes that represent dialog states, connection requests, and property management
/// for SIP-based telephony applications.
///
/// ## Core Classes
///
/// - [Dialog] - Represents a complete SIP dialog with request URI, local party, remote party, and custom attributes
/// - [DialogRequest] - Request model for creating new dialogs with callback URL support and party information
/// - [DialogResponse] - Response model containing dialog attributes after operations
/// - [DialogConnectRequest] - Request model for connecting two existing dialogs using dialog identifiers
/// - [DialogProperties] - Read-only view of dialog properties including remote party and attributes
/// - [DialogPutAttributes] - Request model for updating dialog attributes with key-value pairs
///
/// ## Data Model Overview
///
/// All dialog classes work with `SipSession` objects to provide a structured interface
/// for SIP dialog management. The classes serve as data transfer objects between HTTP API
/// endpoints and the underlying SIP servlet container, enabling third-party call control
/// operations through well-defined data structures.
///
/// ### Key Features
///
/// - **Attribute Management**: All dialog classes support custom attribute maps for storing
///   session-specific key-value pairs
/// - **SIP Integration**: Classes provide constructors that accept `SipSession` objects for
///   seamless integration with SIP servlet containers
/// - **Party Information**: Support for tracking local and remote party identifiers in dialogs
/// - **Connection Support**: Specialized request models for connecting existing dialogs
///
/// ## Usage Context
///
/// These classes enable REST-like interfaces for SIP dialog management by providing
/// structured data models that abstract SIP session complexity. They facilitate
/// third-party call control scenarios where external applications need to manage
/// SIP dialogs without direct SIP protocol handling.
///
/// @see Dialog
/// @see DialogRequest
/// @see DialogConnectRequest
/// @see DialogProperties
/// @see DialogPutAttributes
/// @see DialogResponse
package org.vorpal.blade.services.tpcc.v1.dialog;
