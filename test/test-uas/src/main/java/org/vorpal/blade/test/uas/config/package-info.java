/// Configuration classes for the Test UAS module, defining error response mappings,
/// SIP header definitions, and state management with human-readable delay parsing.
///
/// ## Key Components
///
/// - [TestUasConfig] - main configuration class with an {@code errorMap} that maps phone numbers to SIP error response codes
/// - [TestUasConfigSample] - default configuration providing sample error mappings and logging/session parameters
/// - [TestUasHeaders] - data model representing a SIP header name/value pair
/// - [TestUasState] - state descriptor with configurable delay parsing supporting multiple time units
///
/// ## Error Response Mapping
///
/// ### TestUasConfig
/// [TestUasConfig] extends {@code Configuration} and defines an {@code errorMap}
/// ({@code HashMap<String, Integer>}) that maps phone numbers (or other string keys)
/// to SIP response status codes. This allows the test UAS to return specific error
/// responses for designated addresses.
///
/// ### TestUasConfigSample Defaults
/// [TestUasConfigSample] populates the error map with sample entries:
///
/// - {@code 18165550404} returns 404 Not Found
/// - {@code 18165550503} returns 503 Service Unavailable
/// - {@code 18165550607} returns 607 Unwanted
///
/// Logging is set to FINER level and session expiration to 900 seconds.
///
/// ## Header and State Models
///
/// ### TestUasHeaders
/// [TestUasHeaders] is a simple JavaBean with {@code header} and {@code value}
/// properties, representing a single SIP header to apply to a response.
///
/// ### TestUasState
/// [TestUasState] defines a state with a configurable delay and a list of
/// [TestUasHeaders] to apply. The {@code getDelayInMilliseconds()} method
/// parses human-readable duration strings with the following suffixes:
///
/// - {@code ms} - milliseconds
/// - {@code s} - seconds
/// - {@code m} - minutes
/// - {@code h} - hours
/// - No suffix - raw millisecond value
///
/// @see org.vorpal.blade.test.uas.UasServlet
/// @see org.vorpal.blade.framework.v2.config.Configuration
package org.vorpal.blade.test.uas.config;
