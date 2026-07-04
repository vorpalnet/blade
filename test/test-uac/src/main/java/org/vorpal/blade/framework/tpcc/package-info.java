/// Callflows and REST APIs for Third-Party Call Control operations. TPCC enables
/// an external application to set up, modify, and tear down SIP calls between
/// two endpoints (Alice and Bob) without either party initiating the call directly.
///
/// ## Key Components
///
/// - [TestReinvite] - a minimal callflow that responds 200 OK to re-INVITE requests for mid-dialog testing
/// - [ThirdPartyCallControl] - a JAX-RS REST endpoint exposing TPCC operations via {@code POST /api/v1/tpcc}
///
/// (An unfinished `Simple` TPCC callflow stub was deleted 2026-07-03; the real
/// TPCC flows live in the tpcc SERVICE — offerless create-and-park plus
/// delayed-offer bridging, per RFC 3725 Flow I.)
///
/// ### TestReinvite Callflow
/// [TestReinvite] provides a simple re-INVITE handler that sends a 200 OK response
/// and waits for the ACK. This is useful for testing mid-dialog session modifications
/// during an established TPCC call.
///
/// ## REST API
///
/// ### ThirdPartyCallControl Endpoint
/// [ThirdPartyCallControl] is a JAX-RS resource annotated with OpenAPI/Swagger
/// definitions. It exposes a single {@code POST /api/v1/tpcc} endpoint that accepts
/// a [MessageRequest][org.vorpal.blade.test.client.MessageRequest] and returns a
/// [MessageResponse][org.vorpal.blade.test.client.MessageResponse]. The endpoint
/// uses {@code @Suspended AsyncResponse} for asynchronous HTTP response handling,
/// allowing the SIP call setup to complete before returning the HTTP result.
///
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.test.client.MessageRequest
/// @see org.vorpal.blade.test.client.MessageResponse
package org.vorpal.blade.framework.tpcc;
