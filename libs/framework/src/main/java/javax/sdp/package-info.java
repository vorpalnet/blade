/// # Session Description Protocol (SDP) API
///
/// This package provides a comprehensive Java API for creating, parsing, and manipulating 
/// Session Description Protocol (SDP) messages as defined in RFC 4566. SDP is widely used 
/// in multimedia communication protocols like SIP, RTSP, and WebRTC to describe session 
/// parameters such as media types, codecs, network addresses, and timing information.
///
/// ## Key Classes
///
/// - [SdpFactory] - Primary factory class for creating and parsing SDP messages and components
/// - [SdpException] - Base exception class for SDP-related errors
/// - [SdpParseException] - Specialized exception for SDP parsing errors
/// - [SdpFactoryException] - Exception thrown by factory operations
/// - [contains] - Utility class defining constants for SDP field containment relationships
/// - [uses] - Utility class defining constants for SDP field usage relationships
///
/// ## Usage Example
///
/// ```java
/// // Create an SDP factory instance
/// SdpFactory factory = SdpFactory.getInstance();
///
/// // Parse an SDP message from a string
/// try {
///     SessionDescription session = factory.createSessionDescription(sdpString);
///     
///     // Access session information
///     Origin origin = session.getOrigin();
///     Vector mediaDescriptions = session.getMediaDescriptions(false);
///     
/// } catch (SdpParseException e) {
///     // Handle parsing errors
///     System.err.println("Failed to parse SDP: " + e.getMessage());
/// }
/// ```
///
/// ## Exception Hierarchy
///
/// All exceptions in this package extend [SdpException], providing a consistent error handling
/// model for SDP operations. Specific exceptions include parsing errors and factory creation
/// failures.
///
/// @see SdpFactory
/// @see SdpException
/// @see SdpParseException
package javax.sdp;
