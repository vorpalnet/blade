/// # SIP Call Transfer REST API
///
/// This package provides a REST API for managing SIP call transfers within the Vorpal Blade framework.
/// It enables inspection of active SIP sessions and initiation of various transfer operations through
/// HTTP endpoints with comprehensive notification mechanisms.
///
/// ## Core Functionality
///
/// The API supports multiple transfer styles including blind transfers and REFER-based transfers.
/// Transfer operations can be configured with different notification mechanisms such as synchronous
/// responses, asynchronous callbacks, or JMS messaging. The system provides full lifecycle tracking
/// of transfer operations from initiation through completion.
///
/// ## Key Components
///
/// ### API Endpoints
/// - [TransferAPI] - Main REST endpoint controller providing session inspection and transfer initiation
///
/// ### Request/Response Models
/// - [TransferRequest] - Request payload for initiating transfers with style, session, and notification configuration
/// - [TransferResponse] - Response containing transfer operation results including event type and status codes
/// - [SessionResponse] - Session inspection response with dialog details, attributes, and group memberships
///
/// ### Configuration Models
/// - [Dialog] - SIP dialog representation with headers, request URI, remote party, and Base64-encoded content
/// - [Target] - Transfer destination specification supporting SIP address, URI, user-part, or account identification
/// - [Transferee] - Identification of the party being transferred with multiple lookup methods
/// - [Notification] - Configuration for transfer result notification delivery with style and routing options
///
/// ### Supporting Types
/// - [DialogKey] - Dialog identification by session attribute name-value pairs
/// - [Header] - SIP header name-value pair representation for custom headers in requests
///
/// ## Transfer Process
///
/// 1. Use session inspection endpoints to identify active dialogs and retrieve session information
/// 2. Submit transfer requests specifying transfer style, source dialog identification, and target destination
/// 3. Configure notification preferences for receiving transfer results (immediate, async, callback, or JMS)
/// 4. Handle transfer lifecycle events (requested, initiated, completed, declined, abandoned) through configured notifications
///
/// ## Session Management
///
/// The API provides comprehensive session inspection capabilities, allowing clients to examine
/// active SIP application sessions, their associated dialogs, session attributes, and group
/// memberships. This information is essential for identifying the correct dialogs to transfer.
///
/// ## Notification Styles
///
/// Transfer operations support multiple notification mechanisms:
/// - Immediate synchronous responses
/// - Asynchronous polling with unique identifiers
/// - REST callback notifications to specified endpoints
/// - JMS messaging to configured queues
///
/// The API integrates with the Vorpal Blade transfer framework to provide RESTful access
/// to SIP call control operations while maintaining full SIP protocol compliance and
/// supporting enterprise integration patterns.
///
/// @see org.vorpal.blade.framework.v2.transfer.Transfer
/// @see org.vorpal.blade.framework.v2.transfer.TransferListener
/// @see org.vorpal.blade.framework.v2.transfer.TransferSettings
package org.vorpal.blade.framework.v2.transfer.api;
