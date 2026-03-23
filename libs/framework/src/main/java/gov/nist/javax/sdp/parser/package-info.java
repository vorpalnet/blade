/// # SDP Parser Package
///
/// This package provides comprehensive parsing functionality for Session Description Protocol (SDP) 
/// messages as defined in RFC 4566. The parsers convert SDP field strings into structured field 
/// objects for programmatic manipulation and validation.
///
/// ## Architecture
///
/// The package follows a factory pattern with specialized parsers for each SDP field type. All 
/// concrete parsers extend the abstract [SDPParser] base class and implement field-specific 
/// parsing logic. The [ParserFactory] provides centralized parser instantiation based on field types.
///
/// ## Core Components
///
/// ### Parser Factory
/// - [ParserFactory] - Factory for creating appropriate parser instances for SDP fields using reflection
///
/// ### Base Classes
/// - [SDPParser] - Abstract base class defining the `parse()` contract for all SDP field parsers
/// - [Lexer] - Lexical analyzer extending `LexerCore` for SDP content tokenization and field name extraction
///
/// ### Field Parsers
/// - [AttributeFieldParser] - Parses SDP attribute fields (a=) into [gov.nist.javax.sdp.fields.AttributeField] objects
/// - [BandwidthFieldParser] - Parses bandwidth specification fields (b=) into [gov.nist.javax.sdp.fields.BandwidthField] objects
/// - [ConnectionFieldParser] - Parses connection data fields (c=) with support for connection addresses
/// - [EmailFieldParser] - Parses email address fields (e=) with display name extraction support
/// - [InformationFieldParser] - Parses information description fields (i=) into [gov.nist.javax.sdp.fields.InformationField] objects
/// - [KeyFieldParser] - Parses encryption key fields (k=) into [gov.nist.javax.sdp.fields.KeyField] objects
/// - [MediaFieldParser] - Parses media description fields (m=) into [gov.nist.javax.sdp.fields.MediaField] objects
/// - [OriginFieldParser] - Parses originator fields (o=) into [gov.nist.javax.sdp.fields.OriginField] objects
/// - [PhoneFieldParser] - Parses phone number fields (p=) with display name and number extraction
/// - [ProtoVersionFieldParser] - Parses protocol version fields (v=) into [gov.nist.javax.sdp.fields.ProtoVersionField] objects
/// - [RepeatFieldParser] - Parses repeat time fields (r=) with typed time support
/// - [SessionNameFieldParser] - Parses session name fields (s=) into [gov.nist.javax.sdp.fields.SessionNameField] objects
/// - [TimeFieldParser] - Parses timing fields (t=) with typed time extraction capabilities
/// - [URIFieldParser] - Parses URI reference fields (u=) into [gov.nist.javax.sdp.fields.URIField] objects
/// - [ZoneFieldParser] - Parses time zone adjustment fields (z=) with sign and typed time support
///
/// ### Message Parser
/// - [SDPAnnounceParser] - High-level parser for complete SDP announcement messages, processing vectors of SDP content
///
/// ## Usage Pattern
///
/// Each field parser is instantiated with the raw SDP field string and provides methods to 
/// parse the content into structured field objects. The parsers handle SDP syntax validation 
/// and throw [java.text.ParseException] for malformed input. The [ParserFactory.createParser] 
/// method dynamically instantiates the appropriate parser based on the field type.
///
/// ## Error Handling
///
/// All parsers throw [java.text.ParseException] when encountering invalid SDP syntax or 
/// malformed field content. The exception messages provide detailed information about 
/// parsing failures for debugging purposes.
///
/// @see gov.nist.javax.sdp.fields
/// @see gov.nist.core.ParserCore
/// @see java.text.ParseException
package gov.nist.javax.sdp.parser;
