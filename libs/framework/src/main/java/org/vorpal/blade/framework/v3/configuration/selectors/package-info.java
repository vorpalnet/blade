/// Extract named values from a connector's payload and write them into
/// the shared [org.vorpal.blade.framework.v3.configuration.Context].
///
/// Every selector is polymorphic via a `type` discriminator
/// ([org.vorpal.blade.framework.v3.configuration.selectors.Selector]
/// is the abstract base). Five concrete subtypes cover the common
/// extraction techniques:
///
/// | `type` | Class | Source format |
/// |---|---|---|
/// | `attribute` | [org.vorpal.blade.framework.v3.configuration.selectors.AttributeSelector] | Named header / map field — plain lookup |
/// | `regex`     | [org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector]     | Regex with named capture groups + expression template |
/// | `json`      | [org.vorpal.blade.framework.v3.configuration.selectors.JsonSelector]      | JsonPath against a JSON response body |
/// | `xml`       | [org.vorpal.blade.framework.v3.configuration.selectors.XmlSelector]       | XPath against an XML payload; optional namespace map |
/// | `sdp`       | [org.vorpal.blade.framework.v3.configuration.selectors.SdpSelector]       | SDP field-code lookup |
///
/// Selectors are peers — none extends another. Chaining one selector's
/// output into another's input is supported by reading from the session
/// state: a later selector's `attribute` can name a key that an earlier
/// selector wrote.
///
/// The common base class handles the `${var}` substitution and writes
/// to the [org.vorpal.blade.framework.v3.configuration.Context] SIP
/// session. Application-session storage and session-indexing are
/// deliberately out of scope here — those concerns are moving to
/// session-level configuration rather than per-selector knobs.
package org.vorpal.blade.framework.v3.configuration.selectors;
