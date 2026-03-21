/// # Session Description Protocol (SDP) Implementation
///
/// This package provides a complete implementation of the Session Description Protocol (SDP) 
/// as defined in RFC 4566. SDP is used to describe multimedia communication sessions for the 
/// purposes of session announcement, session invitation, and parameter negotiation.
///
/// ## Key Components
///
/// - [SessionDescriptionImpl] - Core implementation of SDP session descriptions containing 
///   session-level information, timing, and media descriptions
/// - [MediaDescriptionImpl] - Represents individual media streams within a session, including 
///   media type, transport protocol, and format specifications
/// - [TimeDescriptionImpl] - Handles session timing information including start/stop times 
///   and repeat intervals
/// - [SdpEncoderImpl] - Provides encoding functionality to serialize SDP objects into 
///   standard SDP text format
///
/// ## Usage Example
///
/// ```java
/// // Create a new session description
/// SessionDescriptionImpl session = new SessionDescriptionImpl();
/// session.setVersion("0");
/// session.setOrigin("user", "123456", "654321", "IN", "IP4", "192.168.1.100");
/// session.setSessionName("Example Session");
///
/// // Add media description
/// MediaDescriptionImpl media = new MediaDescriptionImpl();
/// media.setMediaType("audio");
/// media.setMediaPort("5004");
/// media.setMediaProtocol("RTP/AVP");
/// session.addMediaDescription(media);
///
/// // Encode to SDP format
/// SdpEncoderImpl encoder = new SdpEncoderImpl();
/// String sdpText = encoder.encode(session);
/// ```
///
/// ## Features
///
/// - Full RFC 4566 compliance for SDP parsing and generation
/// - Support for multiple media descriptions per session
/// - Comprehensive session timing and scheduling capabilities
/// - Flexible attribute handling for session and media-level parameters
/// - Connection information management for unicast and multicast scenarios
///
/// @see javax.sdp
/// @see SessionDescriptionImpl
/// @see MediaDescriptionImpl
package gov.nist.javax.sdp;
