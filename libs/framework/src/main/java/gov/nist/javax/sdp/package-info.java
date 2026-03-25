/// This package provides a comprehensive implementation of the Session Description Protocol (SDP)
/// based on the JSR 141 specification. It contains concrete implementations of the core SDP
/// interfaces for creating, parsing, and manipulating SDP session descriptions.
///
/// ## Core Implementation Classes
///
/// ### Session and Media Description
///
/// - [SessionDescriptionImpl] - Complete implementation of the `SessionDescription` interface,
///   providing functionality for managing SDP session parameters including protocol version,
///   origin, session name, connection details, bandwidth specifications, time descriptions,
///   media descriptions, zone adjustments, and session attributes. Features cloning support
///   and copy constructor for deep copying of session descriptions.
/// - [MediaDescriptionImpl] - Implementation of `MediaDescription` for handling media
///   description components within SDP sessions, including media fields, connection information,
///   bandwidth specifications, key fields, and media attributes. Supports MIME type extraction,
///   dynamic payload handling, and precondition fields for IMS integration.
/// - [TimeDescriptionImpl] - Implementation of `TimeDescription` for managing timing
///   information in SDP sessions including session start/stop times and repeat intervals
///   with support for multiple repeat time specifications.
///
/// ### Encoding and Processing
///
/// - [SdpEncoderImpl] - Provides SDP encoding capabilities with configurable character set
///   specification, typed-time field generation, and RTP attribute mapping. Outputs formatted
///   SDP content to streams with customizable encoding options including support for
///   readable time formats and automatic rtpmap attribute generation.
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
/// - Full SDP session description creation and manipulation with deep cloning support
/// - Dynamic media description handling with MIME type computation and codec parameter extraction
/// - Flexible time description support with repeat scheduling capabilities
/// - Configurable SDP encoding with multiple character set support and typed-time formatting
/// - Integration with IMS precondition mechanisms for advanced media negotiation
/// - Field-level validation and exception handling through `SdpException`
/// - Thread-safe collections using `Vector` for SDP component lists
///
/// ## Thread Safety
///
/// The implementation classes use `Vector` collections for thread-safe access
/// to lists of SDP components, though individual field modifications are not synchronized.
///
/// ## Sub-packages
///
/// ### [gov.nist.javax.sdp.fields]
/// Provides complete implementations of all standard SDP fields from RFC 2327 with JSR 141
/// compliance. Includes session-level fields like [ProtoVersionField][gov.nist.javax.sdp.fields.ProtoVersionField], [OriginField][gov.nist.javax.sdp.fields.OriginField], and
/// [ConnectionField][gov.nist.javax.sdp.fields.ConnectionField], media-level [MediaField][gov.nist.javax.sdp.fields.MediaField], and supporting data structures for connection
/// addresses, typed times, and precondition attributes. All field classes extend [SDPField][gov.nist.javax.sdp.fields.SDPField]
/// and implement corresponding `javax.sdp` interfaces.
///
/// ### [gov.nist.javax.sdp.parser]
/// Contains specialized parsers for converting SDP field strings into structured field objects.
/// The [ParserFactory][gov.nist.javax.sdp.parser.ParserFactory] provides centralized parser instantiation, while individual parsers like
/// [ConnectionFieldParser][gov.nist.javax.sdp.parser.ConnectionFieldParser], [MediaFieldParser][gov.nist.javax.sdp.parser.MediaFieldParser], and [OriginFieldParser][gov.nist.javax.sdp.parser.OriginFieldParser] handle field-specific
/// syntax validation. The [SDPAnnounceParser][gov.nist.javax.sdp.parser.SDPAnnounceParser] processes complete SDP messages with support
/// for mixed line ending formats.
///
/// @see [SessionDescriptionImpl]
/// @see [MediaDescriptionImpl]
/// @see [TimeDescriptionImpl]
/// @see [SdpEncoderImpl]
/// @see gov.nist.javax.sdp.fields
package gov.nist.javax.sdp;
