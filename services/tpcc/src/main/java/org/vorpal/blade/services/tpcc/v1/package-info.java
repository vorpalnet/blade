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
/// - `POST /api/v1/session` - Creates a session, returns {sessionId}
/// - `POST /api/v1/dialog/{sessionId}` - Creates a leg, returns {sessionId, dialogId}
/// - `GET /api/v1/dialog/{sessionId}` - Lists the session's legs
/// - `GET /api/v1/dialog/{sessionId}/{dialogId}` - Returns a leg's properties
/// - `PUT /api/v1/dialog/{sessionId}/{dialogId}` - Sets a leg's attributes
/// - `PUT /api/v1/dialog/{sessionId}/{dialogA}/connect/{dialogB}` - Bridges two legs
/// - `DELETE /api/v1/dialog/{sessionId}/{dialogId}` - Tears a leg down (BYE)
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
/// ## Detailed Class Reference
///
/// ### DialogAPI
///
/// Main REST controller at path `api/v1`, extending `Callflow` and implementing `Serializable`.
/// Annotated with `@OpenAPIDefinition` for Swagger documentation. Contains an inner class
/// `ResponseStuff` that carries a `UriInfo`, an `AsyncResponse`, and the sessionId for
/// tracking pending asynchronous REST requests. Maintains a static
/// `ConcurrentHashMap<String, ResponseStuff>` for correlating SIP session IDs with their
/// REST responses. Provides the following endpoints:
///
/// ### POST dialog/{sessionId}
///
/// Creates a new SIP dialog within an existing application session. Looks up the session by
/// key, creates an OFFERLESS INVITE (RFC 3725 Flow I; the ACK answers the party's offer with an RFC 3264 inactive SDP), stores
/// the `AsyncResponse` in the response map keyed by SIP session ID, and delegates to
/// [CreateDialog][org.vorpal.blade.services.tpcc.callflows.CreateDialog] to send the INVITE asynchronously.
///
/// ### GET dialog/{sessionId}
///
/// Lists every leg in the session as a `SessionGetResponse` (dialogs keyed by dialogId),
/// so a UI can discover and poll call state.
///
/// ### GET dialog/{sessionId}/{dialogId}
///
/// Returns the properties of one leg, located by its Vorpal dialog id (`findLeg`), as a
/// `DialogProperties` response object.
///
/// ### PUT dialog/{sessionId}/{dialogId}
///
/// Updates dialog attributes by setting custom session attributes (prefixed with `3pcc_`)
/// on the matching SIP session. Accepts a `DialogPutAttributes` request body containing
/// a map of key-value pairs.
///
/// ### DELETE dialog/{sessionId}/{dialogId}
///
/// Terminates a dialog by sending a BYE request on the matching SIP session. The response
/// is returned asynchronously through the JAX-RS `AsyncResponse`.
///
/// ### PUT dialog/{sessionId}/{dialogId}/connect/{dialogId2}
///
/// Connects two existing dialogs using a delayed offer/answer exchange. Sends an empty
/// re-INVITE to the first dialog (Alice), forwards Alice's SDP offer to the second dialog
/// (Bob) via re-INVITE, then completes the exchange by sending ACKs with the appropriate
/// SDP to both parties. Uses `TpccServlet.responseMap` for async response tracking.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.services.tpcc.v1.dialog]
/// Provides data transfer objects for SIP dialog management including [Dialog][org.vorpal.blade.services.tpcc.v1.dialog.Dialog],
/// [DialogResponse][org.vorpal.blade.services.tpcc.v1.dialog.DialogResponse], [DialogProperties][org.vorpal.blade.services.tpcc.v1.dialog.DialogProperties],
/// and [DialogPutAttributes][org.vorpal.blade.services.tpcc.v1.dialog.DialogPutAttributes]. These classes bridge HTTP API endpoints and the
/// underlying SIP servlet container, abstracting SIP session complexity for
/// third-party call control scenarios.
///
/// ### [org.vorpal.blade.services.tpcc.v1.session]
/// Provides REST API data transfer objects for TPCC session lifecycle management.
/// [SessionCreateRequest][org.vorpal.blade.services.tpcc.v1.session.SessionCreateRequest] defines session creation parameters including expiration,
/// groups, and custom attributes, while [SessionGetResponse][org.vorpal.blade.services.tpcc.v1.session.SessionGetResponse] returns complete session
/// state with associated dialogs. Supports configurable invalidation policies and
/// group-based session organization.
///
/// @see DialogAPI
/// @see org.vorpal.blade.services.tpcc.TpccServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
package org.vorpal.blade.services.tpcc.v1;
