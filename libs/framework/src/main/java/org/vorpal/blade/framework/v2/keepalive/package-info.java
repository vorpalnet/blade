/// # SIP Session Keep-Alive Framework
///
/// Provides comprehensive SIP session keep-alive functionality for Back-to-Back User Agent (B2BUA) 
/// applications. This package enables maintaining active SIP sessions and RTP media streams through 
/// periodic refresh mechanisms and automatic session termination on timeout.
///
/// ## Key Classes
///
/// - [KeepAlive] - Core keep-alive mechanism that refreshes RTP streams by sending re-INVITE 
///   messages on both call legs of a B2BUA session
/// - [KeepAliveExpiry] - Timeout handler that automatically terminates SIP calls when keep-alive 
///   intervals are exceeded
///
/// ## Functionality
///
/// The keep-alive mechanism works by:
/// 1. Periodically sending re-INVITE messages to refresh media sessions
/// 2. Monitoring session activity and detecting timeouts
/// 3. Gracefully terminating inactive sessions when keep-alive fails
///
/// This ensures that:
/// - RTP streams remain active through NAT devices and firewalls
/// - Inactive or orphaned sessions are properly cleaned up
/// - Network resources are efficiently managed in long-running B2BUA applications
///
/// ## Usage
///
/// The keep-alive functionality is typically configured as part of the SIP application framework
/// and operates automatically once enabled for specific call sessions.
///
/// ```java
/// // Keep-alive is generally configured at the framework level
/// // and operates transparently for managed SIP sessions
/// ```
///
/// @since 2.0
/// @see KeepAlive
/// @see KeepAliveExpiry
package org.vorpal.blade.framework.v2.keepalive;