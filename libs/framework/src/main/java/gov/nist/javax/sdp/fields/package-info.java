/// # SDP Fields Package
///
/// This package provides comprehensive implementation of Session Description Protocol (SDP) field classes
/// as defined in RFC 4566. It contains all the necessary components for parsing, manipulating, and
/// generating SDP protocol messages used in multimedia session descriptions.
///
/// ## Overview
///
/// The Session Description Protocol (SDP) is used to describe multimedia sessions for the purposes
/// of session announcement, session invitation, and other forms of multimedia session initiation.
/// This package implements all standard SDP fields and provides utilities for working with SDP data.
///
/// ## Core Field Classes
///
/// ### Session Description Fields
/// - [SessionNameField] - Session name information (s=)
/// - [InformationField] - Session or media information (i=)
/// - [URIField] - URI reference for additional information (u=)
/// - [OriginField] - Session originator and session identifier (o=)
/// - [ProtoVersionField] - SDP protocol version (v=)
///
/// ### Time Description Fields
/// - [TimeField] - Session time information (t=)
/// - [RepeatField] - Repeat times for sessions (r=)
/// - [ZoneField] - Time zone adjustments (z=)
/// - [ZoneAdjustment] - Individual time zone adjustment entries
/// - [TypedTime] - Utility class for time representations
///
/// ### Media Description Fields
/// - [MediaField] - Media type and transport protocol (m=)
/// - [ConnectionField] - Connection information (c=)
/// - [ConnectionAddress] - Network address components
/// - [BandwidthField] - Bandwidth specifications (b=)
/// - [AttributeField] - Session and media attributes (a=)
/// - [KeyField] - Encryption key information (k=)
///
/// ### Contact Information Fields
/// - [EmailField] - Email contact information (e=)
/// - [EmailAddress] - Email address parsing and formatting
/// - [Email] - Email address representation
/// - [PhoneField] - Phone contact information (p=)
///
/// ### Specialized Fields
/// - [PreconditionFields] - Quality of Service preconditions
/// - [SDPFormat] - Media format specifications
///
/// ## Utility Classes
///
/// - [SDPFieldList] - Generic list container for SDP fields
/// - [SDPObjectList] - Base list implementation for SDP objects
///
/// ## Usage Example
///
/// ```java
/// // Creating a basic session description
/// SessionNameField sessionName = new SessionNameField();
/// sessionName.setSessionName("My Video Conference");
/// 
/// OriginField origin = new OriginField();
/// origin.setUsername("alice");
/// origin.setSessionId(123456789);
/// origin.setNetworkAddress("192.168.1.100");
/// 
/// MediaField media = new MediaField();
/// media.setMediaType("video");
/// media.setPort(5004);
/// media.setProtocol("RTP/AVP");
/// 
/// // Creating connection information
/// ConnectionField connection = new ConnectionField();
/// connection.setNetworkType("IN");
/// connection.setAddressType("IP4");
/// connection.setAddress("224.0.1.1/255");
/// ```
///
/// ## Field Format Specification
///
/// Each SDP field follows the format `<type>=<value>` where:
/// - `<type>` is a single character identifying the field type
/// - `<value>` is the field-specific data
///
/// This package handles the parsing and generation of all standard SDP field types
/// according to RFC 4566 specifications.
///
/// @see javax.sdp
/// @see java.net.URI
package gov.nist.javax.sdp.fields;
