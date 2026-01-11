package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/**
 * Represents a translation rule with attributes, request URI, and nested translation maps.
 */
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Translation implements Serializable {
	private static final long serialVersionUID = 1L;
	private String id;
	private String description;
	private List<TranslationsMap> list;
	private String requestUri;
	private Map<String, Object> attributes;

	/**
	 * Default constructor for JSON deserialization.
	 */
	public Translation() {
	}

	/**
	 * Constructs a Translation with the specified ID.
	 *
	 * @param id the unique identifier for this translation
	 */
	public Translation(String id) {
		this.id = id;
	}

	/**
	 * Copy constructor that creates a new Translation from an existing one.
	 *
	 * @param that the Translation to copy from
	 */
	public Translation(Translation that) {
		if (that != null) {
			this.id = that.id;
			this.description = that.description;
			this.list = that.list;
			this.requestUri = that.requestUri;

			this.attributes = new HashMap<>();
			if (that.attributes != null && !that.attributes.isEmpty()) {
				this.attributes.putAll(that.attributes);
			}

		}
	}

	/**
	 * Returns the human-readable description of this translation.
	 *
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Sets the attributes map for this translation.
	 *
	 * @param attributes the attributes to set
	 * @return this Translation for method chaining
	 */
	public Translation setAttributes(Map<String, String> attributes) {
		this.attributes = new HashMap<>(attributes);
		return this;
	}

	/**
	 * Returns the unique identifier for this translation.
	 *
	 * @return the translation ID
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the unique identifier for this translation.
	 *
	 * @param id the translation ID
	 * @return this Translation for method chaining
	 */
	public Translation setId(String id) {
		this.id = id;
		return this;
	}

	/**
	 * Returns the list of nested translation maps.
	 *
	 * @return the list of translation maps
	 */
	public List<TranslationsMap> getList() {
		return list;
	}

	/**
	 * Sets the list of nested translation maps.
	 *
	 * @param list the list of translation maps
	 * @return this Translation for method chaining
	 */
	public Translation setList(List<TranslationsMap> list) {
		this.list = list;
		return this;
	}

	/**
	 * Returns the request URI for this translation.
	 *
	 * @return the request URI
	 */
	public String getRequestUri() {
		return requestUri;
	}

	/**
	 * Sets the request URI for this translation.
	 *
	 * @param requestUri the request URI
	 * @return this Translation for method chaining
	 */
	public Translation setRequestUri(String requestUri) {
		this.requestUri = requestUri;
		return this;
	}

	/**
	 * Returns the attributes map for this translation.
	 *
	 * @return the attributes map
	 */
	public Map<String, Object> getAttributes() {
		return attributes;
	}

	/**
	 * Sets the human-readable description of this translation.
	 *
	 * @param description the description
	 * @return this Translation for method chaining
	 */
	public Translation setDescription(String description) {
		this.description = description;
		return this;
	}

	/**
	 * Adds a single attribute to this translation.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @return this Translation for method chaining
	 */
	@JsonIgnore
	public Translation addAttribute(String key, Object value) {
		attributes = (attributes != null) ? attributes : new HashMap<>();
		attributes.put(key, value);
		return this;
	}

	/**
	 * Retrieves an attribute value by key.
	 *
	 * @param key the attribute key
	 * @return the attribute value, or null if not found
	 */
	@JsonIgnore
	public Object getAttribute(String key) {
		return (attributes != null) ? attributes.get(key) : null;
	}

}