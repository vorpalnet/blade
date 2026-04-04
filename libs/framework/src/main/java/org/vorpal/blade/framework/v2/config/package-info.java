/// The configuration package provides JSON-based configuration management with
/// automatic schema generation, a three-tier merge hierarchy, JMX runtime reload,
/// and a flexible routing engine built on selectors and translation maps.
///
///
/// ## Config File Basics
///
/// Every BLADE application is configured by a JSON file. The base
/// {@link org.vorpal.blade.framework.v2.config.Configuration Configuration} class
/// defines three standard top-level sections that all applications inherit:
///
/// <pre>{@code
/// {
///   "logging":   { ... },
///   "session":   { ... },
///   "analytics": { ... }
/// }
/// }</pre>
///
/// Applications extend {@link org.vorpal.blade.framework.v2.config.Configuration Configuration}
/// and add their own properties alongside these. For example, the transfer service adds
/// {@code "selectors"}, {@code "maps"}, {@code "plan"}, and {@code "defaultTransferStyle"}.
/// See the {@linkplain org.vorpal.blade.framework.v2.logging logging package} for
/// details on the {@code "logging"} section.
///
///
/// ## File Hierarchy
///
/// Config files live under the WebLogic domain's {@code config/custom/vorpal/} directory.
/// The framework loads and merges them in a three-tier hierarchy:
///
/// <ol>
///   <li><b>Domain</b> &mdash; {@code config/custom/vorpal/<i>app</i>.json}
///       &mdash; organization-wide defaults</li>
///   <li><b>Cluster</b> &mdash; {@code config/custom/vorpal/_clusters/<i>cluster</i>/<i>app</i>.json}
///       &mdash; environment-specific overrides</li>
///   <li><b>Server</b> &mdash; {@code config/custom/vorpal/_servers/<i>server</i>/<i>app</i>.json}
///       &mdash; machine-specific overrides</li>
/// </ol>
///
/// Each tier merges into and overwrites the previous one. All tiers are optional&mdash;if
/// no config files exist, the application's built-in sample configuration is used.
///
/// In addition to the config files themselves, the framework auto-generates two artifacts
/// on first deployment:
///
/// <ul>
///   <li><b>JSON Schema</b> &mdash; {@code config/custom/vorpal/_schemas/<i>app</i>.jschema}
///       &mdash; for validation, IDE autocompletion, and the Configurator admin tool</li>
///   <li><b>Sample config</b> &mdash; {@code config/custom/vorpal/_samples/<i>app</i>.json}
///       &mdash; a starting-point template with sensible defaults</li>
/// </ul>
///
///
/// ## Runtime Reload
///
/// Configuration can be reloaded at runtime via JMX without restarting the application.
/// {@link org.vorpal.blade.framework.v2.config.SettingsManager SettingsManager} registers
/// a {@link org.vorpal.blade.framework.v2.config.SettingsMXBean SettingsMXBean} for each
/// application. Calling {@code reload()} re-reads all three tiers, re-merges them, and
/// applies the new configuration. The Configurator admin tool provides a UI for this,
/// or you can invoke it directly through any JMX client.
///
///
/// ## Selectors
///
/// A {@link org.vorpal.blade.framework.v2.config.Selector Selector} extracts a lookup
/// key from a SIP message using a regex pattern with named capturing groups:
///
/// <pre>{@code
/// {
///   "id": "dialed",
///   "description": "Extract the user part of the To header",
///   "attribute": "To",
///   "pattern": "^(?:\"?(?<name>.*?)\"?\\s*)[<]*(?<proto>sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*$",
///   "expression": "${user}"
/// }
/// }</pre>
///
/// When a request arrives with {@code To: <sip:alice@example.com>}, this selector
/// extracts the key {@code "alice"}.
///
/// The {@code attribute} field specifies where to extract from: any standard SIP header
/// name (e.g. {@code "To"}, {@code "From"}, {@code "P-Charge-Info"}), plus the special
/// values {@code "Request-URI"}, {@code "Content"} (the message body), and
/// {@code "Remote-IP"} (the sender's IP address).
///
///
/// ## Translation Maps
///
/// Translation maps are lookup tables that map extracted keys to routing rules.
/// Each map references one or more selectors and contains a collection of
/// {@link org.vorpal.blade.framework.v2.config.Translation Translation} entries.
///
/// The framework provides several map types optimized for different lookup strategies:
///
/// <table>
///   <caption>Translation Map Types</caption>
///   <tr><th>JSON Type</th><th>Class</th><th>Lookup Strategy</th><th>Use Case</th></tr>
///   <tr>
///     <td>{@code "hash"}</td>
///     <td>{@link org.vorpal.blade.framework.v2.config.ConfigHashMap ConfigHashMap}</td>
///     <td>Exact match</td>
///     <td>Username routing, exact phone numbers</td>
///   </tr>
///   <tr>
///     <td>{@code "prefix"}</td>
///     <td>{@link org.vorpal.blade.framework.v2.config.ConfigPrefixMap ConfigPrefixMap}</td>
///     <td>Longest prefix match</td>
///     <td>Dialed number routing, area codes</td>
///   </tr>
///   <tr>
///     <td>{@code "address"}</td>
///     <td>{@link org.vorpal.blade.framework.v2.config.ConfigAddressMap ConfigAddressMap}</td>
///     <td>CIDR-based IP match (IPv4 and IPv6)</td>
///     <td>Source-IP routing, carrier identification</td>
///   </tr>
///   <tr>
///     <td>{@code "linked"}</td>
///     <td>{@link org.vorpal.blade.framework.v2.config.ConfigLinkedHashMap ConfigLinkedHashMap}</td>
///     <td>Exact match (preserves insertion order)</td>
///     <td>Same as hash, but ordered for readability</td>
///   </tr>
///   <tr>
///     <td>{@code "tree"}</td>
///     <td>{@link org.vorpal.blade.framework.v2.config.ConfigTreeMap ConfigTreeMap}</td>
///     <td>Exact match (sorted keys)</td>
///     <td>Same as hash, but sorted for debugging</td>
///   </tr>
/// </table>
///
/// ### Hash Map Example
///
/// <pre>{@code
/// {
///   "type": "hash",
///   "id": "user-map",
///   "selectors": ["dialed"],
///   "map": {
///     "alice": { "requestUri": "sip:alice@backend.local" },
///     "bob":   { "requestUri": "sip:bob@backend.local" }
///   }
/// }
/// }</pre>
///
/// ### Prefix Map Example
///
/// <pre>{@code
/// {
///   "type": "prefix",
///   "id": "area-code-map",
///   "selectors": ["dialed"],
///   "map": {
///     "1816555": { "requestUri": "sip:kc-local@gateway" },
///     "1816":    { "requestUri": "sip:kc@gateway" },
///     "1":       { "requestUri": "sip:domestic@gateway" }
///   }
/// }
/// }</pre>
///
/// If the dialed number is {@code 18165554321}, the prefix map tries
/// {@code 18165554321}, {@code 1816555432}, {@code 181655543}, ... until it finds the
/// longest matching prefix {@code 1816555} and returns that translation.
///
/// ### Address Map Example
///
/// <pre>{@code
/// {
///   "type": "address",
///   "id": "ip-map",
///   "selectors": ["source-ip"],
///   "map": {
///     "192.168.1.0/24": { "requestUri": "sip:office@backend" },
///     "10.0.0.0/8":     { "requestUri": "sip:vpn@backend" }
///   }
/// }
/// }</pre>
///
///
/// ## Translations
///
/// A {@link org.vorpal.blade.framework.v2.config.Translation Translation} is a single
/// routing rule within a map. It can set a destination URI, attach session attributes,
/// or contain nested maps for multi-level routing:
///
/// <pre>{@code
/// {
///   "id": "alice-route",
///   "requestUri": "sip:alice@backend.local",
///   "attributes": {
///     "billing_code": "premium",
///     "destination_group": "vips"
///   }
/// }
/// }</pre>
///
/// The {@code requestUri} field supports {@code ${variable}} substitution using values
/// from session attributes or captured regex groups. For example,
/// {@code "sip:${user}@backend.local"} resolves the {@code user} variable at runtime.
///
///
/// ## Routing Plans
///
/// Applications that extend
/// {@link org.vorpal.blade.framework.v2.config.RouterConfig RouterConfig} use a
/// {@code "plan"} to define the order in which translation maps are consulted. The plan
/// is simply an ordered list of map IDs:
///
/// <pre>{@code
/// {
///   "selectors": [ ... ],
///   "maps": [
///     { "type": "hash",   "id": "exact-map",  ... },
///     { "type": "prefix", "id": "prefix-map",  ... }
///   ],
///   "plan": ["exact-map", "prefix-map"],
///   "defaultRoute": { "requestUri": "sip:operator@backend" }
/// }
/// }</pre>
///
/// When a request arrives, the framework walks the plan in order:
///
/// <ol>
///   <li>For each map in the plan, extract a key using the map's selectors</li>
///   <li>Look up the key in the map</li>
///   <li>If a match is found, return that translation</li>
///   <li>If no map matches, use the {@code defaultRoute} (if defined)</li>
/// </ol>
///
/// This allows multi-tier routing: check exact matches first, then fall back to
/// prefix-based matching, then fall back to a default.
///
///
/// ## Session Parameters
///
/// The {@code "session"} section configures SIP session lifecycle via
/// {@link org.vorpal.blade.framework.v2.config.SessionParameters SessionParameters}:
///
/// <pre>{@code
/// {
///   "session": {
///     "expiration": 60,
///     "keepAlive": {
///       "style": "UPDATE",
///       "sessionExpires": 1800,
///       "minSE": 90
///     },
///     "sessionSelectors": [ ... ]
///   }
/// }
/// }</pre>
///
/// The {@code sessionSelectors} are
/// {@link org.vorpal.blade.framework.v2.config.AttributeSelector AttributeSelector}
/// objects that extract values from the initial INVITE and store them as SIP session
/// attributes. These attributes are then available for variable substitution in
/// translation maps and for analytics event reporting.
///
///
/// ## Complete Example
///
/// Putting it all together, here is a minimal but complete routing configuration:
///
/// <pre>{@code
/// {
///   "logging": {
///     "loggingLevel": "INFO",
///     "sequenceDiagramLoggingLevel": "INFO"
///   },
///   "session": {
///     "expiration": 60
///   },
///   "selectors": [
///     {
///       "id": "dialed",
///       "attribute": "To",
///       "pattern": "^.*sip:(?<user>[^@]*)@.*$",
///       "expression": "${user}"
///     }
///   ],
///   "maps": [
///     {
///       "type": "hash",
///       "id": "exact-map",
///       "selectors": ["dialed"],
///       "map": {
///         "alice": { "requestUri": "sip:alice@10.0.0.1" },
///         "bob":   { "requestUri": "sip:bob@10.0.0.2" }
///       }
///     }
///   ],
///   "plan": ["exact-map"],
///   "defaultRoute": { "requestUri": "sip:operator@10.0.0.99" }
/// }
/// }</pre>
///
/// This configuration extracts the username from the To header, looks it up in an
/// exact-match map, and routes to the corresponding backend. If no match is found,
/// the call goes to the operator.
///
///
/// ## Core Classes
///
/// ### Configuration and Loading
///
/// - {@link org.vorpal.blade.framework.v2.config.Configuration Configuration} - Base config class with logging, session, and analytics sections
/// - {@link org.vorpal.blade.framework.v2.config.RouterConfig RouterConfig} - Extends Configuration with selectors, maps, plan, and defaultRoute for routing applications
/// - {@link org.vorpal.blade.framework.v2.config.SettingsManager SettingsManager} - Loads, merges, and manages configuration lifecycle with JMX integration
/// - {@link org.vorpal.blade.framework.v2.config.Settings Settings} - JMX MBean implementation for runtime config file operations
/// - {@link org.vorpal.blade.framework.v2.config.SettingsMXBean SettingsMXBean} - JMX interface for reload, read, and write operations
///
/// ### Selectors and Routing
///
/// - {@link org.vorpal.blade.framework.v2.config.Selector Selector} - Extracts lookup keys from SIP messages using regex patterns
/// - {@link org.vorpal.blade.framework.v2.config.AttributeSelector AttributeSelector} - Extracts session attributes from SIP messages with dialog association
/// - {@link org.vorpal.blade.framework.v2.config.TranslationsMap TranslationsMap} - Abstract base class for all translation map types
/// - {@link org.vorpal.blade.framework.v2.config.Translation Translation} - A single routing rule with destination URI and attributes
///
/// ### Map Implementations
///
/// - {@link org.vorpal.blade.framework.v2.config.ConfigHashMap ConfigHashMap} - Exact match (HashMap)
/// - {@link org.vorpal.blade.framework.v2.config.ConfigPrefixMap ConfigPrefixMap} - Longest prefix match
/// - {@link org.vorpal.blade.framework.v2.config.ConfigAddressMap ConfigAddressMap} - CIDR-based IP match (IPv4/IPv6)
/// - {@link org.vorpal.blade.framework.v2.config.ConfigLinkedHashMap ConfigLinkedHashMap} - Ordered exact match
/// - {@link org.vorpal.blade.framework.v2.config.ConfigTreeMap ConfigTreeMap} - Sorted exact match
///
/// ### Session Management
///
/// - {@link org.vorpal.blade.framework.v2.config.SessionParameters SessionParameters} - Session expiration, keep-alive, and session selectors
/// - {@link org.vorpal.blade.framework.v2.config.KeepAliveParameters KeepAliveParameters} - Keep-alive style, session-expires, and min-SE values
///
/// ### Conditions and Matching
///
/// - {@link org.vorpal.blade.framework.v2.config.Condition Condition} - Collections of comparisons for complex request matching
/// - {@link org.vorpal.blade.framework.v2.config.Comparison Comparison} - Individual comparison operations for SIP header matching
///
/// @see org.vorpal.blade.framework.v2.config.Configuration
/// @see org.vorpal.blade.framework.v2.config.SettingsManager
/// @see org.vorpal.blade.framework.v2.config.RouterConfig
/// @see org.vorpal.blade.framework.v2.config.Selector
/// @see org.vorpal.blade.framework.v2.config.TranslationsMap
package org.vorpal.blade.framework.v2.config;
