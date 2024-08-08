package org.vorpal.blade.framework.v3.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.vorpal.blade.framework.v3.config.TranslationsMap;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Translation<T> implements Serializable {
	private String id;
	private String description;
	private T attributes;
	private LinkedList<TranslationsMap<T>> list;

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
			this.attributes = that.attributes;
		}
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

	public T getAttributes() {
		return attributes;
	}

	public void setAttributes(T attributes) {
		this.attributes = attributes;
	}

}