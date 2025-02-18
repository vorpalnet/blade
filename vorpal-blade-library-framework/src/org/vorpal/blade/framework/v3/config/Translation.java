package org.vorpal.blade.framework.v3.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Translation<T> implements Serializable {
	private static final long serialVersionUID = 1L;
	private String id;
	private String description;
	private LinkedList<TranslationsMap<T>> list;
	private Map<String, String> attributes = new HashMap<>();
	private T properties;

	public Translation() {
	}

	public Translation(String id) {
		this.id = id;
	}

	public Translation(Translation<T> that) {
		if (that != null) {
			this.id = that.id;
			this.description = that.description;
			this.list = that.list;

			if (attributes != null) {
				this.attributes = new HashMap<>(that.attributes);
			} else {
				this.attributes = new HashMap<>();
			}

		}
	}

	public T getProperties() {
		return properties;
	}

	public void setProperties(T properties) {
		this.properties = properties;
	}

	public String getDescription() {
		return description;
	}

	public Translation<T> setDescription(String description) {
		this.description = description;
		return this;
	}

	public String getId() {
		return id;
	}

	public Translation<T> setId(String id) {
		this.id = id;
		return this;
	}

	public LinkedList<TranslationsMap<T>> getList() {
		return list;
	}

	public Translation<T> setList(LinkedList<TranslationsMap<T>> list) {
		this.list = list;
		return this;
	}

	public Map<String, String> getAttributes() {
		return attributes;
	}

	public void setAttributes(Map<String, String> attributes) {
		this.attributes = new HashMap<>(attributes);
	}

	@JsonIgnore
	public Translation addAttribute(String key, String value) {
		attributes = (null != attributes) ? attributes : new HashMap<>();
		attributes.put(key, value);
		return this;
	}

	@JsonIgnore
	public Object getAttribute(String key) {
		return (null != attributes) ? attributes.get(key) : null;
	}

}