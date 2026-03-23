/// # TPCC Dialog API v1
///
/// This package provides RESTful API endpoints for managing SIP dialogs in the TPCC (Third Party Call Control) service.
/// It implements a comprehensive dialog management system that bridges HTTP REST operations with SIP protocol handling.
///
/// ## Core Functionality
///
/// The package enables third-party applications to control SIP dialogs through standard HTTP operations:
///
/// - **Dialog Creation** - Establish new SIP dialogs via POST requests
/// - **Dialog Retrieval** - Query dialog state and properties via GET requests  
/// - **Dialog Modification** - Update dialog attributes via PUT requests
/// - **Dialog Connection** - Connect two dialogs together for call bridging
/// - **Dialog Termination** - End active dialogs via DELETE requests
///
/// ## Key Classes
///
/// - [DialogAPI] - Main REST controller extending `Callflow` that provides HTTP endpoints for dialog operations
///   with asynchronous request processing and SIP integration
/// - `Dialog` - Data model representing a SIP dialog with its properties and state
/// - `DialogProperties` - Configuration and metadata associated with dialog instances
/// - `DialogPutAttributes` - Request payload for updating dialog attributes
///
/// ## API Endpoints
///
/// The [DialogAPI] class provides the following REST endpoints:
///
/// - `POST /api/v1/dialog/{sessionId}` - Creates a new dialog
/// - `GET /api/v1/dialog/{sessionId}/{dialogId}` - Returns dialog properties
/// - `DELETE /api/v1/dialog/{sessionId}/{dialogId}` - Tears down a dialog
/// - `PUT /api/v1/dialog/{sessionId}/{dialogId}` - Sets dialog properties
/// - `PUT /api/v1/dialog/{sessionId}/{dialog01}/connect/{dialog02}` - Connects two dialogs together
///
/// ## Integration Points
///
/// The API integrates with the Vorpal Blade framework's callflow system and leverages:
///
/// - [org.vorpal.blade.framework.v2.callflow.Callflow] for SIP call processing
/// - [org.vorpal.blade.services.tpcc.TpccServlet] for core TPCC functionality
/// - [org.vorpal.blade.services.tpcc.callflows.CreateDialog] for dialog creation workflows
///
/// ## Technical Features
///
/// - **Asynchronous Processing** - Non-blocking request handling with `AsyncResponse` and response mapping
/// - **OpenAPI Documentation** - Swagger annotations for comprehensive API specification
/// - **Session Management** - Integration with SIP application and dialog sessions
/// - **Thread Safety** - Concurrent access support using `ConcurrentHashMap` for response tracking
/// - **SIP Protocol Bridge** - Direct SIP servlet request processing through the `process` method
///
/// @see DialogAPI

/// @see org.vorpal.blade.services.tpcc.TpccServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
package org.vorpal.blade.services.tpcc.v1;
