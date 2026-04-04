/// # SIP Presence Service
///
/// This package implements a SIP-based presence service that handles PUBLISH and SUBSCRIBE 
/// operations for real-time presence information exchange. The service is built on the 
/// Vorpal Blade framework and provides a complete solution for managing presence events 
/// and subscriber notifications.
///
/// ## Architecture
///
/// The presence service follows an event-driven architecture where:
/// - Publishers use PUBLISH requests to announce their presence state
/// - Subscribers use SUBSCRIBE requests to receive presence notifications
/// - Events are organized by account ID and event name
/// - Each event maintains a list of subscribers with expiration management
///
/// ## Key Components
///
/// - [PresenceServlet] - Main SIP servlet that handles incoming requests and routes them to appropriate callflows
/// - [PublishCallflow] - Processes PUBLISH requests for presence state updates
/// - [SubscribeCallflow] - Handles SUBSCRIBE requests for presence event notifications
/// - [Event] - Core data structure representing a presence event with associated subscribers
/// - [PresenceSettings] - Configuration settings for the presence service
///
/// ## Request Flow
///
/// ### PUBLISH Operations
/// Requests are organized by account_id (From header) containing an EventMap where each 
/// event includes content, content type, originator information, and subscriber lists.
///
/// ### SUBSCRIBE Operations
/// Session identification uses the combination of From header and Event name. Session 
/// expiration is managed based on the Expires header value, with automatic cleanup 
/// when subscriptions expire.
///
/// @see PresenceServlet
/// @see PublishCallflow
/// @see SubscribeCallflow
/// @see Event
package org.vorpal.blade.services.presence;
