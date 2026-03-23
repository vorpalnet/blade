/// # NIST SDP Implementation Package
///
/// This package provides a comprehensive implementation of the Session Description Protocol (SDP)
/// based on the JSR 141 specification. It contains concrete implementations of the core SDP
/// interfaces for creating, parsing, and manipulating SDP session descriptions.
///
/// ## Core Implementation Classes
///
/// ### Session and Media Description
///
/// - [SessionDescriptionImpl] - Complete implementation of the [javax.sdp.SessionDescription] interface,
///   providing functionality for managing SDP session parameters, connection information, media
///   descriptions, and session attributes. Maintains protocol version, origin, session name,
///   connection details, bandwidth specifications, and collections of time and media descriptions.
/// - [MediaDescriptionImpl] - Implementation of [javax.sdp.MediaDescription] for handling media
///   description components within SDP sessions, including media fields, connection information,
///   bandwidth specifications, key fields, and media attributes. Supports encoding to SDP format.
/// - [TimeDescriptionImpl] - Implementation of [javax.sdp.TimeDescription] for managing timing
///   information in SDP sessions including session start/stop times and repeat intervals.
///
/// ### Encoding and Processing
///
/// - [SdpEncoderImpl] - Provides SDP encoding capabilities with configurable character set
///   specification, typed-time field generation, and RTP attribute mapping. Outputs formatted
///   SDP content to streams with customizable encoding options.
///
/// ## Implementation Architecture
///
/// All implementation classes integrate with the field-level implementations from the
/// `gov.nist.javax.sdp.fields` package, providing a layered architecture where:
///
/// - High-level session and media descriptions manage collections of SDP fields
/// - Individual field implementations handle low-level SDP syntax and parsing
/// - Encoder components provide output formatting and character encoding support
///
/// ## Key Features
///
/// This implementation supports:
///
/// - Full SDP session description creation and manipulation with cloning support
/// - Dynamic media description handling with attribute and bandwidth management
/// - Flexible time description support with repeat scheduling capabilities
/// - Configurable SDP encoding with multiple character set support and typed-time formatting
/// - Integration with the broader NIST SIP/SDP framework
/// - Field-level validation and exception handling through [javax.sdp.SdpException]
///
/// ## Thread Safety
///
/// The implementation classes use [java.util.Vector] collections for thread-safe access
/// to lists of SDP components, though individual field modifications are not synchronized.
///
/// @see javax.sdp.SessionDescription
/// @see javax.sdp.MediaDescription
/// @see javax.sdp.TimeDescription
/// @see javax.sdp.SdpException
/// @see gov.nist.javax.sdp.fields
package gov.nist.javax.sdp;
