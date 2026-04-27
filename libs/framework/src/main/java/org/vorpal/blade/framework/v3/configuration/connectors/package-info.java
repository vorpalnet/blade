/// Pipeline stages — each connector contributes values to the shared
/// per-call [org.vorpal.blade.framework.v3.configuration.Context] so
/// downstream stages can use them in `${var}` templates.
///
/// Every connector is polymorphic via a `type` discriminator at
/// serialization time ([org.vorpal.blade.framework.v3.configuration.connectors.Connector]
/// is the abstract base). Concrete subtypes:
///
/// | `type` | Class | Payload source |
/// |---|---|---|
/// | `sip`   | [org.vorpal.blade.framework.v3.configuration.connectors.SipConnector]   | The inbound `SipServletRequest` — headers, Request-URI, body, derived remote IP |
/// | `rest`  | [org.vorpal.blade.framework.v3.configuration.connectors.RestConnector]  | HTTP response body from an asynchronously-dispatched request; supports polymorphic authentication and HTTP-message body templates |
/// | `jdbc`  | [org.vorpal.blade.framework.v3.configuration.connectors.JdbcConnector]  | First row of a parameterized SQL query against a WebLogic `DataSource` |
/// | `ldap`  | [org.vorpal.blade.framework.v3.configuration.connectors.LdapConnector]  | First entry of an LDAP search |
/// | `map`   | [org.vorpal.blade.framework.v3.configuration.connectors.MapConnector]   | Entry matched from an inline key→attributes map |
/// | `table` | [org.vorpal.blade.framework.v3.configuration.connectors.TableConnector] | First-match-wins lookup across a list of [org.vorpal.blade.framework.v3.configuration.translations.TranslationTable]s |
///
/// Each connector (except `table`) carries a list of
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector]s
/// that parse its payload and write named values into the Context.
/// `TableConnector` doesn't need selectors — the matched Translation's
/// extras spread directly.
///
/// Connectors run sequentially on the SIP container thread until they
/// return their `CompletableFuture<Void>`, at which point the
/// container thread is released. REST/JDBC/LDAP connectors complete
/// asynchronously; SIP, Map, and Table complete instantly.
package org.vorpal.blade.framework.v3.configuration.connectors;
