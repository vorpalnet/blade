/// # Session Description Protocol (SDP) API
///
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
/// - [MediaDescription] - Describes individual media streams (audio, video, etc.) within sessions
/// - [TimeDescription] - Manages session timing information including start/stop times and repeat schedules
///
/// ### SDP Field Interfaces
/// The package provides interfaces for all standard SDP fields as defined in RFC 2327:
///
/// #### Session Information Fields
/// - [Version] - SDP version field (`v=`) - currently version 0
/// - [Origin] - Session originator information (`o=`) including username, session ID, and network details
/// - [SessionName] - Required session name field (`s=`)
/// - [Info] - Optional session or media information field (`i=`)
/// - [URI] - Optional URI for additional session details (`u=`)
///
/// #### Contact Fields
/// - [EMail] - Email contact information (`e=`)
/// - [Phone] - Phone contact information (`p=`)
///
/// #### Network and Media Fields
/// - [Connection] - Network connection information (`c=`) specifying network type, address type, and address
/// - [Media] - Media format specifications (`m=`) defining media type, port, protocol, and formats
/// - [Attribute] - Session and media attributes (`a=`) supporting both name-only and name-value pairs
/// - [BandWidth] - Bandwidth specifications (`b=`) with predefined CT and AS modifiers
///
/// #### Timing Fields
/// - [Time] - Session start and stop times (`t=`) using NTP timestamps
/// - [RepeatTime] - Repeat scheduling information (`r=`) for recurring sessions
/// - [TimeZoneAdjustment] - Timezone adjustments (`z=`) for sessions spanning time changes
///
/// #### Security Fields
/// - [Key] - Encryption key information (`k=`) with method and key data
///
/// ### Base Infrastructure
/// - [Field] - Base interface for all SDP fields providing serialization, cloning, and type identification
/// - [SdpConstants] - Comprehensive constants for RTP/AVP payload types, protocol identifiers, and codec information
///
/// ## Key Features
///
/// - **RFC 2327 Compliance** - Complete implementation of all standard SDP fields and structures
/// - **RTP/AVP Support** - Extensive constants for Real-time Transport Protocol Audio/Video Profile
/// - **Flexible Object Creation** - Factory-based creation with multiple construction options
/// - **String Parsing** - Parse complete SDP messages from string representations
/// - **Customizable Encoding** - Control output formatting, typed times, and character encoding
/// - **Detailed Error Reporting** - Exception hierarchy with precise line and character position information
/// - **Network Protocol Support** - IPv4/IPv6 addressing and multiple transport protocols
/// - **Time Handling** - NTP time conversion utilities and typed time formatting
///
/// ## Protocol Support Details
///
/// ### Media Types and Formats
/// The API supports standard media types (audio, video, application, data, control) with comprehensive
/// RTP payload type mappings including static assignments for common codecs (PCMU, GSM, H.261, etc.)
/// and dynamic payload type ranges.
///
/// ### Network Addressing
/// Supports Internet (IN) network type with IPv4 and IPv6 address types, including multicast
/// addressing with TTL and address count specifications.
///
/// ### Bandwidth Management
/// Provides Conference Total (CT) and Application Specific (AS) bandwidth modifiers for
/// session and media-level bandwidth specifications measured in kilobits per second.
///
/// ### Session Timing
/// Handles complex timing scenarios including one-time sessions, recurring sessions with
/// repeat intervals, and timezone adjustments for sessions spanning daylight saving changes.
///
/// @see SessionDescription
/// @see SdpFactory
/// @see MediaDescription
/// @see SdpEncoder
package javax.sdp;
