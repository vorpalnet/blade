package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/// A polymorphic external data source that resolves a routing key into
/// a [Translation] by querying a remote system.
///
/// ## When resolvers run
///
/// Resolvers are queried by [RouterConfiguration.findTranslation] only
/// after the local [TranslationTable] plan has been exhausted without a
/// match. This keeps the fast path (local hash/prefix lookup) free of
/// network latency and reserves external calls for keys that genuinely
/// require dynamic resolution.
///
/// The full resolution order in [RouterConfiguration]:
///
/// 1. **Selectors** extract a routing key and session attributes from
///    the inbound SIP message.
/// 2. **Local plan** — [TranslationTable]s are searched in sequence.
/// 3. **Resolvers** — queried in sequence if no local match. Each
///    resolver receives the routing key and all session attributes
///    collected by the selectors, so `${var}` placeholders in URLs,
///    headers, and request body templates can be populated.
/// 4. **Default route** — final fallback if nothing matches.
///
/// ## Implementing a resolver
///
/// Concrete implementations must provide both `resolve` methods. The
/// single-argument form delegates to the two-argument form with a null
/// attributes map.
///
/// Current implementations:
///
/// - [RestResolver] — queries an HTTP/REST API using GET or POST
///
/// Future implementations (registered via `@JsonSubTypes`):
///
/// - `JdbcResolver` — executes a SQL query against a JDBC data source
/// - `LdapResolver` — searches an LDAP directory
///
/// ## JSON configuration
///
/// Resolvers are configured as a polymorphic list in the
/// [RouterConfiguration] JSON file:
///
/// ```json
/// {
///   "resolvers": [
///     {
///       "type": "rest",
///       "id": "customer-api",
///       "url": "https://api.example.com/route/${user}",
///       ...
///     }
///   ]
/// }
/// ```
///
/// The `type` discriminator selects the concrete implementation.
///
/// @param <T> the treatment type carried by the returned [Translation]
///            (must match the [RouterConfiguration]'s type parameter)
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = RestResolver.class, name = "rest"),
		@JsonSubTypes.Type(value = JdbcResolver.class, name = "jdbc"),
		@JsonSubTypes.Type(value = LdapResolver.class, name = "ldap")
})
public interface Resolver<T> extends Serializable {

	@JsonPropertyDescription("Unique identifier for this resolver")
	String getId();

	void setId(String id);

	@JsonPropertyDescription("Human-readable description")
	String getDescription();

	void setDescription(String description);

	/// Resolve a routing key by querying the external system.
	///
	/// Convenience method that delegates to [#resolve(String, Map)]
	/// with a null attributes map. Use when no session attributes
	/// are available (e.g. in tests or standalone lookups).
	///
	/// @param key the routing key extracted by a [Selector]
	/// @return a [Translation] containing the treatment, or `null`
	///         if the external system has no match for this key
	/// @throws Exception if the external call fails (network error,
	///         timeout, authentication failure, malformed response)
	Translation<T> resolve(String key) throws Exception;

	/// Resolve a routing key with session attributes available for
	/// variable substitution.
	///
	/// The `attributes` map contains named values extracted by prior
	/// [Selector]s (e.g. `user`, `host`, `callingNumber`). Concrete
	/// resolvers use these to populate `${var}` placeholders in URLs,
	/// request headers, and body templates before making the external
	/// call.
	///
	/// @param key        the routing key extracted by a [Selector]
	/// @param attributes session attributes from prior selectors,
	///                   or `null` if none are available
	/// @return a [Translation] containing the treatment, or `null`
	///         if the external system has no match for this key
	/// @throws Exception if the external call fails
	Translation<T> resolve(String key, Map<String, String> attributes) throws Exception;
}
