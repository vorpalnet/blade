/// This package contains call flow implementations for Third Party Call Control (TPCC) services.
/// It provides specialized call flow handlers that integrate with the Vorpal Blade SIP framework
/// to manage dialog creation and control operations.
///
/// ## Key Classes
///
/// - [CreateDialog] - Client call flow implementation for creating SIP dialogs through TPCC operations.
///   Extends `ClientCallflow` to handle SIP servlet requests and integrate with the TPCC dialog API.
///
/// ## Overview
///
/// The call flows in this package extend the Vorpal Blade framework's client call flow capabilities
/// to provide TPCC-specific functionality. These implementations handle SIP servlet requests and
/// integrate with the TPCC dialog API to manage call control operations.
///
/// The [CreateDialog] class serves as the primary entry point for TPCC dialog creation operations,
/// processing incoming SIP INVITE requests and coordinating with the dialog API to establish
/// controlled call sessions.
///
/// The package is part of the larger TPCC service architecture and works in conjunction with
/// the dialog API components to provide comprehensive third-party call control capabilities.
///
/// ## Detailed Class Reference
///
/// ### CreateDialog
///
/// Extends `ClientCallflow` to handle SIP dialog creation initiated by the REST API.
/// The `invoke(SipServletRequest)` method sends the INVITE request and registers a
/// callback that handles the response:
///
/// - On a **successful** response (2xx), sends an ACK, removes the pending
///   `ResponseStuff` entry from `DialogAPI.responseMap`, and resumes the JAX-RS
///   `AsyncResponse` with the SIP status and reason phrase.
/// - On a **failure** response (4xx/5xx/6xx), retrieves the pending `ResponseStuff`
///   and resumes the `AsyncResponse` with the SIP error status.
///
/// This class bridges the asynchronous SIP INVITE/response exchange with the
/// JAX-RS asynchronous REST response model, enabling the REST caller to receive
/// the SIP outcome as an HTTP status code.
///
/// @see [org.vorpal.blade.framework.v2.callflow.ClientCallflow]
/// @see [org.vorpal.blade.services.tpcc.v1.DialogAPI]
package org.vorpal.blade.services.tpcc.callflows;
