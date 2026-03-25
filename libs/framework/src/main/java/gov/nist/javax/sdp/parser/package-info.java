/// This package provides comprehensive parsing functionality for Session Description Protocol (SDP) 
/// messages as defined in RFC 4566. The parsers convert SDP field strings into structured field 
/// objects for programmatic manipulation and validation.
///
/// ## Architecture
///
/// The package follows a factory pattern with specialized parsers for each SDP field type. All 
/// concrete parsers extend the abstract [SDPParser] base class and implement field-specific 
/// parsing logic. The [ParserFactory] provides centralized parser instantiation based on field types
/// using reflection to dynamically create appropriate parser instances.
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
/// The package includes specialized parsers for all standard SDP field types:
///
/// - [AttributeFieldParser] - Parses SDP attribute fields (a=)
/// - [BandwidthFieldParser] - Parses bandwidth specification fields (b=)
/// - [ConnectionFieldParser] - Parses connection data fields (c=) with connection address support
/// - [EmailFieldParser] - Parses email address fields (e=) with display name extraction
/// - [InformationFieldParser] - Parses information description fields (i=)
/// - [KeyFieldParser] - Parses encryption key fields (k=)
/// - [MediaFieldParser] - Parses media description fields (m=)
/// - [OriginFieldParser] - Parses originator fields (o=) with support for large numeric values
/// - [PhoneFieldParser] - Parses phone number fields (p=) with display name and number extraction
/// - [ProtoVersionFieldParser] - Parses protocol version fields (v=)
/// - [RepeatFieldParser] - Parses repeat time fields (r=) with typed time support
/// - [SessionNameFieldParser] - Parses session name fields (s=), handles empty session names
/// - [TimeFieldParser] - Parses timing fields (t=) with typed time extraction capabilities
/// - [URIFieldParser] - Parses URI reference fields (u=)
/// - [ZoneFieldParser] - Parses time zone adjustment fields (z=) with sign and typed time support
///
/// ### Message Parser
/// - [SDPAnnounceParser] - High-level parser for complete SDP announcement messages, processing 
///   vectors of SDP content with support for mixed line ending formats (CR, LF, CR/LF, LF/CR)
///
/// ## Usage Pattern
///
/// Each field parser is instantiated with the raw SDP field string and provides methods to 
/// parse the content into structured field objects. The parsers handle SDP syntax validation 
/// and throw `ParseException` for malformed input. The [ParserFactory] `createParser` 
/// method dynamically instantiates the appropriate parser based on the field type.
///
/// ## Error Handling
///
/// All parsers throw `ParseException` when encountering invalid SDP syntax or 
/// malformed field content. The exception messages provide detailed information about 
/// parsing failures for debugging purposes. The parsers are designed to be robust,
/// handling edge cases like empty fields and mixed line endings.
///
/// ## Special Features
///
/// - Support for large numeric values (>18 digits) in origin and time fields
/// - Robust handling of mixed line ending formats in SDP announcements
/// - Typed time parsing with unit support (seconds, minutes, hours, days)
/// - Email and phone number parsing with display name extraction
/// - Connection address parsing for network configuration
///
/// @see [gov.nist.javax.sdp.fields.SDPField]
/// @see [gov.nist.core.ParserCore]
/// @see [java.text.ParseException]
package gov.nist.javax.sdp.parser;
