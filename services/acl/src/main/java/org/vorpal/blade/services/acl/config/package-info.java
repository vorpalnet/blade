/// This package contains utility and test classes for the Access Control List service's
/// IP address matching infrastructure.
///
/// ## Key Components
///
/// - [Test1] - Test harness demonstrating IPv4 and IPv6 address trie operations
///
/// ## Detailed Class Reference
///
/// ### Test1
///
/// A standalone test class with a `main` method for exercising the IPAddress library's
/// trie data structures. Contains four test methods:
///
/// - `test1()` -- demonstrates IPv6 trie population, IPv4 trie construction with CIDR
///   blocks and ranges, and the `elementsContaining` lookup for longest-prefix matching
/// - `test2()` -- tests IP address range notation (e.g., `192.168.1.1-9`) and the
///   `isMultiple` and `toAllStringCollection` methods
/// - `test3()` -- similar range containment tests with sequential range validation
/// - `test4()` -- demonstrates `IPv4AddressAssociativeTrie` with `AddressTrieMap` for
///   mapping addresses and CIDR blocks to string values (the pattern used by [AclConfig][org.vorpal.blade.services.acl.AclConfig])
///
/// These tests validate the address matching approach used by the ACL service before
/// it was integrated into the main [AclConfig][org.vorpal.blade.services.acl.AclConfig] class.
///
/// @see [org.vorpal.blade.services.acl.AclConfig]
/// @see [org.vorpal.blade.services.acl.AclRule]
package org.vorpal.blade.services.acl.config;
