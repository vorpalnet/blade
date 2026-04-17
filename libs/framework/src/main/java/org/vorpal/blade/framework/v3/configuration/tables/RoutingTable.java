package org.vorpal.blade.framework.v3.configuration.tables;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/// A routing table is the *decision* step. It resolves
/// `${keyExpression}` from session attributes and looks the
/// resulting key up in its `entries` using its strategy
/// (exact match, longest-prefix, etc.). The matched entry —
/// itself a `Map<String,String>` — is the **Treatment**.
///
/// Treatments commonly contain a `requestUri` (the proxy target),
/// but they can carry any name/value pairs the iRouter or service
/// wants to consume. The iRouter typically resolves
/// `${requestUri}` from the matched Treatment to determine the
/// outbound destination.
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
		defaultImpl = HashRoutingTable.class)
@JsonSubTypes({
		@JsonSubTypes.Type(value = HashRoutingTable.class, name = "hash"),
		@JsonSubTypes.Type(value = PrefixRoutingTable.class, name = "prefix"),
		@JsonSubTypes.Type(value = LinkedRoutingTable.class, name = "linked"),
		@JsonSubTypes.Type(value = TreeRoutingTable.class, name = "tree")
})
@JsonPropertyOrder({ "type", "id", "description", "keyExpression", "entries" })
public abstract class RoutingTable implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String id;
	protected String description;
	protected String keyExpression;
	protected Map<String, Map<String, String>> entries = new LinkedHashMap<>();

	@JsonPropertyDescription("Unique identifier for this table")
	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	@JsonPropertyDescription("Human-readable description")
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	@JsonPropertyDescription("${var} template that produces the lookup key, e.g. ${actionDirective}")
	public String getKeyExpression() { return keyExpression; }
	public void setKeyExpression(String keyExpression) { this.keyExpression = keyExpression; }

	@JsonPropertyDescription("Map of key to Treatment (a name/value map)")
	public Map<String, Map<String, String>> getEntries() { return entries; }
	public void setEntries(Map<String, Map<String, String>> entries) {
		this.entries = (entries != null) ? entries : new LinkedHashMap<>();
	}

	/// Resolve the key from the context and return the matching
	/// Treatment, or null if nothing matches.
	public Map<String, String> match(Context ctx) {
		if (keyExpression == null || ctx == null) return null;
		String key = ctx.resolve(keyExpression);
		if (key == null || key.equals(keyExpression)) return null;
		return lookup(key);
	}

	/// Strategy-specific lookup. Hash does exact match, prefix
	/// does longest-prefix, etc.
	protected abstract Map<String, String> lookup(String key);
}
