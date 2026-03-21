/// # Transfer API Package
///
/// Provides REST API data transfer objects and endpoints for SIP call transfer operations.
///
/// This package contains the complete API layer for REST-based transfer functionality,
/// enabling external systems to initiate, monitor, and manage call transfers through
/// a well-defined interface.
///
/// ## Core API Components
///
/// - [TransferAPI] - Main REST endpoint implementation with 10 methods for transfer operations
/// - [TransferRequest] - Request payload containing transfer parameters
/// - [TransferResponse] - Response object for transfer operation results
///
/// ## Data Transfer Objects
///
/// - [Dialog] - SIP dialog representation with comprehensive session state (10 fields)
/// - [DialogKey] - Unique identifier for dialog sessions
/// - [Target] - Transfer destination address specification
/// - [Transferee] - Identification of the party being transferred
/// - [Header] - SIP header manipulation utilities (5 methods)
/// - [SessionResponse] - Detailed session response data (8 methods, 12 fields)
/// - [Notification] - Configuration for transfer notification styles
///
/// ## Usage Example
///
/// ```java
/// // Create a transfer request
/// TransferRequest request = new TransferRequest();
/// request.setTransferee(transferee);
/// request.setTarget(target);
///
/// // Execute transfer through API
/// TransferAPI api = new TransferAPI();
/// TransferResponse response = api.initiateTransfer(request);
/// ```
///
/// ## API Integration
///
/// The classes in this package are designed for seamless integration with REST clients
/// and provide JSON serialization support for web-based transfer management systems.
/// The [TransferAPI] class serves as the primary entry point for all transfer operations.
///
/// @see org.vorpal.blade.framework.v2.transfer
package org.vorpal.blade.framework.v2.transfer.api;
