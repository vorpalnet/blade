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
/// - [PresenceServlet] - Main SIP servlet extending `AsyncSipServlet` that handles incoming requests and routes them to appropriate callflows
/// - [PublishCallflow] - Processes PUBLISH requests for presence state updates
/// - [SubscribeCallflow] - Handles SUBSCRIBE requests for presence event notifications
/// - [Event] - Core data structure representing a presence event with associated subscribers
/// - [PresenceSettings] - Serializable configuration settings for the presence service
///
/// ## Request Processing
///
/// The [PresenceServlet] serves as the entry point for all SIP requests and uses a callflow-based 
/// architecture to route requests to the appropriate handler. The servlet includes session key 
/// generation, lifecycle management, and callflow selection logic.
///
/// ### PUBLISH Operations
/// 
/// Handled by [PublishCallflow], these requests are organized by account_id (From header) 
/// containing an EventMap where each event includes content, content type, originator 
/// information, and subscriber lists.
///
/// ### SUBSCRIBE Operations
/// 
/// Managed by [SubscribeCallflow], session identification uses the combination of From header 
/// and Event name. Session expiration is managed based on the Expires header value, with 
/// automatic cleanup when subscriptions expire.
///
/// ## Configuration
///
/// The service uses [PresenceSettings] for configuration management through a static 
/// `SettingsManager` instance maintained by [PresenceServlet].
///
/// @see PresenceServlet
/// @see PublishCallflow  
/// @see SubscribeCallflow
/// @see Event
/// @see PresenceSettings
package org.vorpal.blade.services.presence;
