/// This package provides a SIP Access Control List (ACL) service that filters incoming
/// SIP requests based on the remote IP address. Requests from allowed addresses are
/// proxied to their destination; requests from denied addresses receive a 403 Forbidden
/// response.
///
/// ## Key Components
///
/// - [AclSipServlet] - Main SIP servlet that intercepts all incoming requests and applies ACL rules
/// - [AclConfig] - Configuration class defining the default permission and a list of address-based rules
/// - [AclConfigManager] - Settings manager that initializes the IP address trie for efficient lookups
/// - [AclRule] - Data class pairing an IP address or CIDR block with an allow/deny permission
///
/// ## Architecture
///
/// The service operates as a SIP proxy servlet. When a request arrives, [AclSipServlet]
/// extracts the remote IP address and calls `AclConfig.evaulate(address)` to determine the
/// permission. If allowed, the request is proxied to its original Request-URI; if denied,
/// a 403 Forbidden response is returned.
///
/// The [AclConfig] class uses an IPv4 address trie (`AddressTrieMap`) from the IPAddress
/// library for efficient longest-prefix-match lookups against CIDR blocks and individual
/// addresses. The trie is built during initialization from the configured list of [AclRule]
/// entries.
///
/// ## Detailed Class Reference
///
/// ### AclSipServlet
///
/// The main servlet annotated with `@SipApplication(distributable=true)` and
/// `@SipServlet(loadOnStartup=1)`. Extends `SipServlet` and implements `SipServletListener`.
/// The `doRequest` method logs extensive request details (headers, attributes, routing
/// information) and then evaluates the remote address against the ACL configuration.
/// Allowed requests are proxied via `request.getProxy().proxyTo(requestURI)`; denied
/// requests receive a 403 response. Servlet initialization creates the [AclConfigManager]
/// and logs the configuration.
///
/// ### AclConfig
///
/// Configuration class containing a `defaultPermission` (deny by default) and a
/// `LinkedList<AclRule>` of remote address rules. The `initialize()` method builds an
/// `AddressTrieMap<Address, Permission>` from the rule list for efficient prefix-based
/// IP lookups. The `evaulate(String address)` method converts the input to an IPv4 address,
/// performs a trie lookup, and returns the matching permission or the default if no match
/// is found. Ships with sample rules for the 192.168.1.0/24 subnet (allow) and
/// 192.168.2.136 (deny).
///
/// ### AclConfigManager
///
/// Extends `SettingsManager<AclConfig>` to provide configuration lifecycle management.
/// Overrides `initialize(AclConfig)` to call `config.initialize()`, which builds the
/// address trie from the configured rules. Accepts a `SipServletContextEvent` for
/// framework integration.
///
/// ### AclRule
///
/// Simple data class pairing a string `address` (IP address or CIDR notation) with a
/// `Permission` enum value (`allow` or `deny`). Provides standard getters and setters
/// along with a convenience constructor.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.services.acl.config]
/// Contains utility and test classes for the ACL service's IP address matching
/// infrastructure. The [Test1][org.vorpal.blade.services.acl.config.Test1] class provides a standalone test harness with four
/// test methods that exercise the IPAddress library's trie data structures:
/// IPv6 trie population, IPv4 trie construction with CIDR blocks and ranges,
/// `elementsContaining` lookups for longest-prefix matching, IP range notation
/// parsing (`isMultiple`, `toAllStringCollection`), and `IPv4AddressAssociativeTrie`
/// with `AddressTrieMap` for mapping addresses and CIDR blocks to values -- the
/// same pattern used by [AclConfig] for runtime ACL evaluation.
///
/// @see AclSipServlet
/// @see AclConfig
/// @see [org.vorpal.blade.framework.v2.config.SettingsManager]
package org.vorpal.blade.services.acl;
