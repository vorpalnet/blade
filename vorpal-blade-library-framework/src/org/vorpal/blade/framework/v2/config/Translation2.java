package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/**
 * Represents a translation rule with attributes and nested translation maps.
 */
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Translation2 implements Serializable {
	private static final long serialVersionUID = 1L;
	private String id;
	private String description;
	private List<TranslationsMap> list;
	private String requestUri;
	private Map<String, String> attributes;

	public Translation2() {
	}

	public Translation2(String id) {
		this.id = id;
	}

	public Translation2(Translation2 that) {
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

	public String getDescription() {
		return description;
	}

	public Translation2 setAttributes(Map<String, String> attributes) {
		this.attributes = new HashMap<>(attributes);
		return this;
	}

	public String getId() {
		return id;
	}

	public Translation2 setId(String id) {
		this.id = id;
		return this;
	}

	public List<TranslationsMap> getList() {
		return list;
	}

	public Translation2 setList(List<TranslationsMap> list) {
		this.list = list;
		return this;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public Translation2 setRequestUri(String requestUri) {
		this.requestUri = requestUri;
		return this;
	}

	public Map<String, String> getAttributes() {
		return attributes;
	}

	public Translation2 setDescription(String description) {
		this.description = description;
		return this;
	}

	@JsonIgnore
	public Translation2 addAttribute(String key, String value) {
		attributes = (attributes != null) ? attributes : new HashMap<>();
		attributes.put(key, value);
		return this;
	}

	@JsonIgnore
	public String getAttribute(String key) {
		return (attributes != null) ? attributes.get(key) : null;
	}

}