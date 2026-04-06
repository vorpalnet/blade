/// Configuration classes for the Test UAS module, defining response behavior
/// defaults, error response mappings, SDP content, and state management with
/// human-readable duration parsing.
///
/// ## Key Components
///
/// - [TestUasConfig] - main configuration extending `Configuration` with
///   `defaultStatus`, `defaultDelay`, `defaultDuration`, `sdpContent`, and
///   `errorMap` for phone-number-to-error-code routing
/// - [TestUasConfigSample] - default configuration with sample error mappings
///   and sensible defaults (200 OK, 0s delay, 30s duration)
/// - [TestUasHeaders] - data model representing a SIP header name/value pair
/// - [TestUasState] - state descriptor with configurable delays supporting
///   multiple time units
///
/// ## Response Defaults
///
/// ### TestUasConfig
/// [TestUasConfig] extends `Configuration` and defines runtime-tunable response
/// behavior:
///
/// - `defaultStatus` — SIP response status code (default: 200)
/// - `defaultDelay` — delay before sending response, human-readable (default: `0s`)
/// - `defaultDuration` — call hold time before auto-BYE (default: `30s`)
/// - `sdpContent` — SDP body for 2xx responses, or null for the built-in default
/// - `errorMap` — `HashMap<String, Integer>` mapping phone numbers to SIP error codes
///
/// Computed getters `getDefaultDelaySeconds()` and `getDefaultDurationSeconds()`
/// parse human-readable durations using `Configuration.parseHRDurationAsSeconds()`.
///
/// All fields use `@JsonPropertyDescription` on getters for automatic JSON Schema
/// generation by the Configurator.
///
/// ### TestUasConfigSample Defaults
/// [TestUasConfigSample] populates the error map with sample entries:
///
/// - `18165550404` returns 404 Not Found
/// - `18165550503` returns 503 Service Unavailable
/// - `18165550607` returns 607 Unwanted
///
/// Logging is set to FINER level and session expiration to 900 seconds.
///
/// ## Header and State Models
///
/// ### TestUasHeaders
/// [TestUasHeaders] is a simple JavaBean with `header` and `value` properties,
/// representing a single SIP header to apply to a response.
///
/// ### TestUasState
/// [TestUasState] defines a state with a configurable delay and a list of
/// [TestUasHeaders] to apply. The `getDelayInMilliseconds()` method parses
/// human-readable duration strings with the following suffixes:
///
/// - `ms` — milliseconds
/// - `s` — seconds
/// - `m` — minutes
/// - `h` — hours
/// - No suffix — raw millisecond value
///
/// @see org.vorpal.blade.test.uas.UasServlet
/// @see org.vorpal.blade.test.uas.api.TestUasAPI
/// @see org.vorpal.blade.framework.v2.config.Configuration
package org.vorpal.blade.test.uas.config;
