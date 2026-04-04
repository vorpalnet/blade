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
/// - **Dialog Termination** - End active dialogs via DELETE requests
///
/// ## Key Classes
///
/// - [DialogAPI] - Main REST controller providing HTTP endpoints for dialog operations
/// - [Dialog] - Data model representing a SIP dialog with its properties and state
/// - [DialogProperties] - Configuration and metadata associated with dialog instances
/// - [DialogPutAttributes] - Request payload for updating dialog attributes
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
/// - **Asynchronous Processing** - Non-blocking request handling with `AsyncResponse`
/// - **OpenAPI Documentation** - Swagger annotations for API specification
/// - **Session Management** - Integration with SIP application and dialog sessions
/// - **Thread Safety** - Concurrent access support for dialog state management
///
/// @see DialogAPI
/// @see Dialog
/// @see org.vorpal.blade.services.tpcc.TpccServlet
package org.vorpal.blade.services.tpcc.v1;
