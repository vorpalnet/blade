/// Provides a comprehensive Java API for parsing, creating, and manipulating Session Description Protocol (SDP) messages
/// as defined in IETF RFC 2327. This package enables applications to handle multimedia session descriptions used in
/// protocols like SIP, RTSP, and SAP.
///
/// ## Core Components
///
/// ### Factory and Parsing
/// - [SdpFactory] - Primary factory for creating SDP objects and parsing SDP strings into structured representations
/// - [SdpEncoder] - Serializes [SessionDescription] objects with customizable formatting options
/// - [SdpParseException] - Exception thrown during SDP parsing errors with line/character position information
/// - [SdpException] - General exception for SDP operations
/// - [SdpFactoryException] - Exception for factory configuration and instantiation errors
///
/// ### Session Structure
/// - [SessionDescription] - Main container representing complete SDP session data with all required and optional fields
/// - [MediaDescription] - Describes individual media streams (audio, video, etc.) within sessions with support for precondition fields
/// - [TimeDescription] - Manages session timing information including start/stop times and repeat schedules
///
/// ### SDP Field Interfaces
/// The package provides interfaces for all standard SDP fields as defined in RFC 2327:
///
/// #### Session Information Fields
/// - [Version] - SDP version field (`v=`) - currently version 0
/// - [Origin] - Session originator information (`o=`) including username, session ID, version, and network details
/// - [SessionName] - Required session name field (`s=`)
/// - [Info] - Optional session or media information field (`i=`)
/// - [URI] - Optional URI for additional session details (`u=`)
///
/// #### Contact Fields
/// - [EMail] - Email contact information (`e=`)
/// - [Phone] - Phone contact information (`p=`)
///
/// #### Network and Media Fields
/// - [Connection] - Network connection information (`c=`) specifying network type (IN), address type (IP4/IP6), and address
/// - [Media] - Media format specifications (`m=`) defining media type, port, port count, protocol, and format lists
/// - [Attribute] - Session and media attributes (`a=`) supporting both name-only and name-value pairs
/// - [BandWidth] - Bandwidth specifications (`b=`) with predefined CT (Conference Total) and AS (Application Specific) modifiers
///
/// #### Timing Fields
/// - [Time] - Session start and stop times (`t=`) using NTP timestamps with typed time formatting support
/// - [RepeatTime] - Repeat scheduling information (`r=`) for recurring sessions with interval, duration, and offset arrays
/// - [TimeZoneAdjustment] - Timezone adjustments (`z=`) for sessions spanning daylight saving time changes
///
/// #### Security Fields
/// - [Key] - Encryption key information (`k=`) with method and key data
///
/// ### Base Infrastructure
/// - [Field] - Base interface for all SDP fields providing serialization, cloning, and type character identification
/// - [SdpConstants] - Comprehensive constants for RTP/AVP payload types, protocol identifiers, codec information, and NTP time conversion
///
/// ## Key Features
///
/// - **RFC 2327 Compliance** - Complete implementation of all standard SDP fields and structures
/// - **RTP/AVP Support** - Extensive constants for Real-time Transport Protocol Audio/Video Profile with static payload types (0-34)
/// - **Flexible Object Creation** - Factory-based creation with multiple construction options for all SDP components
/// - **String Parsing** - Parse complete SDP messages from string representations with detailed error reporting
/// - **Customizable Encoding** - Control output formatting, typed times, rtpmap attributes, and character encoding
/// - **Detailed Error Reporting** - Exception hierarchy with precise line and character position information
/// - **Network Protocol Support** - IPv4/IPv6 addressing and multiple transport protocols
/// - **Time Handling** - NTP time conversion utilities and typed time formatting (d/h/m/s units)
/// - **MIME Type Generation** - Automatic MIME type and parameter extraction from media descriptions
/// - **Dynamic Payload Support** - Support for dynamic RTP payload types beyond static assignments
///
/// ## Protocol Support Details
///
/// ### Media Types and Formats
/// The API supports standard media types (audio, video, application, data, control) with comprehensive
/// RTP payload type mappings including static assignments for common codecs (PCMU=0, GSM=3, H.261=31, etc.)
/// and dynamic payload type ranges (≥96).
///
/// ### Network Addressing
/// Supports Internet (IN) network type with IPv4 and IPv6 address types, including multicast
/// addressing with TTL and address count specifications through [Connection] interface.
///
/// ### Bandwidth Management
/// Provides Conference Total (CT) and Application Specific (AS) bandwidth modifiers for
/// session and media-level bandwidth specifications measured in kilobits per second.
///
/// ### Session Timing
/// Handles complex timing scenarios including one-time sessions, recurring sessions with
/// repeat intervals and offset arrays, and timezone adjustments for sessions spanning daylight saving changes.
/// Time values can be formatted as integers or typed time units.
///
/// ### Codec Support
/// Includes predefined constants for standard audio codecs (PCMU, PCMA, GSM, G722, G728, G729) and
/// video codecs (H.261, H.263, JPEG, MPV) with corresponding clock rates and channel information.
///
/// @see SessionDescription
/// @see SdpFactory
/// @see MediaDescription
/// @see SdpEncoder
/// @see SdpConstants
package javax.sdp;
