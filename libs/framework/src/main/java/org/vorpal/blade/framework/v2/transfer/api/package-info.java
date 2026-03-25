/// This package provides a comprehensive REST API for managing SIP call transfers within the Vorpal Blade framework.
/// It enables inspection of active SIP sessions and initiation of various transfer operations through
/// HTTP endpoints with flexible notification mechanisms and full lifecycle tracking.
///
/// ## Core Functionality
///
/// The API supports multiple transfer styles including blind transfers, attended transfers, conference transfers,
/// and REFER-based transfers. Transfer operations can be configured with different notification mechanisms such as
/// synchronous responses, asynchronous callbacks, REST callbacks, or JMS messaging. The system provides complete
/// lifecycle tracking of transfer operations from initiation through completion with detailed event reporting.
///
/// ## Key Components
///
/// ### API Controller
/// - [TransferAPI] - Main REST endpoint controller extending `ClientCallflow` and implementing `TransferListener`
///   for session inspection and transfer operation management
///
/// ### Request/Response Models
/// - [TransferRequest] - Request payload for initiating transfers with style, session identification, dialog selection,
///   notification preferences, and target destination
/// - [TransferResponse] - Response containing transfer operation results including event type, SIP method, status codes,
///   and descriptions
/// - [SessionResponse] - Session inspection response providing session attributes, group memberships, and dialog details
///
/// ### Configuration Models
/// - [Dialog] - SIP dialog representation with ID, request URI, remote party, INVITE headers, and Base64-encoded content
/// - [Target] - Transfer destination specification supporting SIP address, URI, user-part, account identification,
///   and additional INVITE headers
/// - [Transferee] - Identification of the party being transferred with multiple lookup methods including dialog ID,
///   SIP addresses, and session attributes
/// - [Notification] - Configuration for transfer result notification delivery with support for immediate, async,
///   callback, and JMS notification styles
///
/// ### Supporting Types
/// - [DialogKey] - Dialog identification by session attribute name-value pairs for locating specific dialogs
/// - [Header] - SIP header name-value pair representation for specifying additional headers in INVITE or REFER requests
///
/// ## Transfer Process
///
/// 1. **Session Inspection**: Use the inspect endpoint to examine active SIP application sessions, retrieve dialog
///    information, session attributes, and group memberships
/// 2. **Transfer Initiation**: Submit transfer requests specifying the transfer style, source session and dialog
///    identification, target destination, and notification preferences
/// 3. **Notification Configuration**: Configure how transfer results should be delivered (immediate response,
///    async polling, REST callback, or JMS messaging)
/// 4. **Lifecycle Management**: Handle transfer events through the configured notification mechanism, including
///    requested, initiated, completed, declined, and abandoned states
///
/// ## Session Management
///
/// The API provides comprehensive session inspection capabilities through [SessionResponse] objects that contain:
/// - Session ID and expiration information
/// - Group memberships for session organization
/// - Session attributes as key-value pairs
/// - Associated SIP dialogs with complete header information
///
/// ## Notification Mechanisms
///
/// Transfer operations support multiple notification styles via the [Notification] configuration:
/// - **Immediate**: Synchronous response with transfer results
/// - **Async**: Asynchronous operation with polling using unique identifiers
/// - **Callback**: REST callback notifications to specified endpoints
/// - **JMS**: Message delivery to configured JMS queues
///
/// ## Transfer Lifecycle Events
///
/// The [TransferAPI] implements `TransferListener` to handle all transfer lifecycle events:
/// - Transfer requested (initial REFER received)
/// - Transfer initiated (outbound INVITE sent)
/// - Transfer completed (successful response received)
/// - Transfer declined (negative response received)
/// - Transfer abandoned (CANCEL request processed)
///
/// ## HTTP Status Codes
///
/// The API uses standard HTTP status codes to indicate transfer operation results:
/// - `200 OK` - Transfer completed successfully
/// - `202 Accepted` - Transfer initiated (fire and forget mode)
/// - `403 Forbidden` - Transfer declined by remote party
/// - `404 Not Found` - Session or dialog not found
/// - `406 Not Acceptable` - Invalid request or target specification
/// - `410 Gone` - Transfer abandoned
/// - `491 Request Pending` - Glare condition detected
/// - `500 Internal Server Error` - Unexpected system error
///
/// This API integrates with the Vorpal Blade transfer framework to provide RESTful access to SIP call control
/// operations while maintaining full SIP protocol compliance and supporting enterprise integration patterns
/// through flexible notification mechanisms.
///
/// @see org.vorpal.blade.framework.v2.transfer.Transfer
/// @see org.vorpal.blade.framework.v2.transfer.TransferListener
/// @see org.vorpal.blade.framework.v2.transfer.TransferSettings
package org.vorpal.blade.framework.v2.transfer.api;
