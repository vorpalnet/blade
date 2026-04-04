/// # SDP Field Implementations
///
/// This package provides complete implementations of Session Description Protocol (SDP) fields
/// as defined in RFC 2327 and JSR 141. It contains concrete field classes that represent
/// all standard SDP header fields and their associated data structures.
///
/// ## Core Field Classes
///
/// ### Session-Level Fields
/// - [ProtoVersionField] - Protocol version field (`v=`) implementing [javax.sdp.Version]
/// - [OriginField] - Origin field (`o=`) containing session originator information
/// - [SessionNameField] - Session name field (`s=`) implementing [javax.sdp.SessionName]
/// - [InformationField] - Information field (`i=`) for session descriptions
/// - [URIField] - URI field (`u=`) for session URIs implementing [javax.sdp.URI]
/// - [EmailField] - Email field (`e=`) for contact information implementing [javax.sdp.EMail]
/// - [PhoneField] - Phone field (`p=`) for contact numbers implementing [javax.sdp.Phone]
/// - [ConnectionField] - Connection field (`c=`) for network connection data
/// - [BandwidthField] - Bandwidth field (`b=`) for bandwidth specifications
/// - [TimeField] - Time field (`t=`) for session timing implementing [javax.sdp.Time]
/// - [RepeatField] - Repeat field (`r=`) for recurring sessions implementing [javax.sdp.RepeatTime]
/// - [ZoneField] - Zone field (`z=`) for timezone adjustments implementing [javax.sdp.TimeZoneAdjustment]
/// - [KeyField] - Key field (`k=`) for encryption keys implementing [javax.sdp.Key]
/// - [AttributeField] - Attribute field (`a=`) for session and media attributes
///
/// ### Media-Level Fields
/// - [MediaField] - Media field (`m=`) defining media streams and formats implementing [javax.sdp.Media]
///
/// ## Supporting Data Structures
///
/// ### Connection and Address Classes
/// - [ConnectionAddress] - Represents connection address information with TTL and port
/// - [Email] - Email address representation with username and hostname components
/// - [EmailAddress] - Complete email address with optional display name
///
/// ### Time and Zone Classes
/// - [TypedTime] - Time values with optional unit specifiers (seconds, minutes, hours, days)
/// - [ZoneAdjustment] - Individual timezone adjustment entries with time offset information
///
/// ### Format and Precondition Classes
/// - [SDPFormat] - Media format representation for encoding format specifications
/// - [PreconditionFields] - Support for precondition attributes (RFC 3312, RFC 4032) with QoS handling
///
/// ## Base Classes and Infrastructure
///
/// ### Abstract Base Classes
/// - [SDPField] - Base class for all SDP field implementations providing common field operations
/// - [SDPFieldList] - Base class for fields containing lists of SDP items
/// - [SDPObject] - Root class providing common SDP object functionality and encoding methods
/// - [SDPObjectList] - Generic list container for SDP objects with merge and iteration capabilities
///
/// ### Utility Classes
/// - [Indentation] - Internal utility for formatting and pretty printing SDP structures
///
/// ## Constants and Interfaces
///
/// - [SDPFieldNames] - Interface defining SDP field name constants (`v=`, `o=`, `s=`, etc.)
/// - [SDPKeywords] - Interface containing SDP-related keywords and constants for protocols and encoding
///
/// ## Key Features
///
/// - **Complete SDP Support**: Implements all standard SDP fields from RFC 2327
/// - **JSR 141 Compliance**: All field classes implement corresponding interfaces from `javax.sdp`
/// - **Encoding/Parsing**: Every field class provides `encode()` methods for SDP serialization
/// - **Type Safety**: Strongly-typed field implementations with proper validation
/// - **Network Protocol Support**: Handles IPv4/IPv6 addressing and various transport protocols
/// - **Extensibility**: Supports custom attributes and precondition extensions
/// - **Clone Support**: Most classes implement proper cloning for object duplication
///
/// All field implementations extend [SDPField] and implement their corresponding interfaces from
/// the `javax.sdp` package, ensuring full compatibility with the Java SDP API specification.
/// The package follows a consistent pattern where each field provides getter/setter methods,
/// encoding capabilities, and JSR 141 compliance methods.
///
/// @see javax.sdp
/// @see gov.nist.javax.sdp
/// @see SDPField
/// @see SDPObject
package gov.nist.javax.sdp.fields;
