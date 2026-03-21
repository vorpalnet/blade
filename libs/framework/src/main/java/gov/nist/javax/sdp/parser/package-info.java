/// # SDP Parser Package
///
/// This package provides a comprehensive set of parsers for Session Description Protocol (SDP) 
/// fields as defined in RFC 4566. It implements a complete parsing framework for processing 
/// SDP messages used in multimedia session negotiations.
///
/// ## Overview
///
/// The Session Description Protocol (SDP) is used to describe multimedia sessions for the 
/// purposes of session announcement, session invitation, and other forms of multimedia session 
/// initiation. This package contains specialized parsers for each type of SDP field, enabling 
/// accurate parsing and validation of SDP content.
///
/// ## Core Components
///
/// ### Main Parser
/// - [SDPAnnounceParser] - Primary parser for complete SDP announcements
/// - [SDPParser] - Base SDP parsing functionality
/// - [ParserFactory] - Factory for creating field-specific parsers
///
/// ### Field Parsers
/// Each SDP field type has a dedicated parser implementation:
///
/// - [ProtoVersionFieldParser] - Protocol version field (`v=`)
/// - [OriginFieldParser] - Origin field (`o=`) containing session originator information
/// - [SessionNameFieldParser] - Session name field (`s=`)
/// - [InformationFieldParser] - Information field (`i=`) for session descriptions
/// - [URIFieldParser] - URI field (`u=`) for additional session information
/// - [EmailFieldParser] - Email field (`e=`) for contact information
/// - [PhoneFieldParser] - Phone field (`p=`) for contact information
/// - [ConnectionFieldParser] - Connection field (`c=`) for network connection data
/// - [BandwidthFieldParser] - Bandwidth field (`b=`) for bandwidth specifications
/// - [TimeFieldParser] - Time field (`t=`) for session timing information
/// - [RepeatFieldParser] - Repeat field (`r=`) for recurring sessions
/// - [ZoneFieldParser] - Time zone field (`z=`) for time zone adjustments
/// - [KeyFieldParser] - Key field (`k=`) for encryption keys
/// - [AttributeFieldParser] - Attribute field (`a=`) for various session attributes
/// - [MediaFieldParser] - Media field (`m=`) for media descriptions
///
/// ### Utility Classes
/// - [Lexer] - Low-level lexical analyzer for tokenizing SDP content
///
/// ## Usage Example
///
/// ```java
/// // Parse a complete SDP announcement
/// SDPAnnounceParser parser = new SDPAnnounceParser();
/// SessionDescription sessionDesc = parser.parse(sdpString);
///
/// // Parse individual fields using factory
/// ParserFactory factory = new ParserFactory();
/// OriginFieldParser originParser = factory.createOriginFieldParser();
/// OriginField origin = originParser.parse("o=alice 2890844526 2890844527 IN IP4 host.atlanta.com");
/// ```
///
/// ## Parser Architecture
///
/// All field parsers follow a consistent pattern:
/// 1. Accept raw SDP field text as input
/// 2. Perform lexical analysis and tokenization
/// 3. Validate syntax according to SDP specifications
/// 4. Create appropriate field objects with parsed data
/// 5. Handle parsing errors with descriptive exceptions
///
/// The parsers are designed to be thread-safe and reusable, making them suitable for 
/// high-performance applications that process large volumes of SDP messages.
///
/// @see SDPAnnounceParser
/// @see ParserFactory
/// @see Lexer
package gov.nist.javax.sdp.parser;
