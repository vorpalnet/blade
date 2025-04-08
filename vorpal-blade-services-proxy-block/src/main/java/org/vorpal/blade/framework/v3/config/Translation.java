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
	public String id;
	public String desc;
	private LinkedList<TranslationsMap<T>> list;
	private Map<String, String> attributes;
	private T route;

	public TranslationsMap<T> addTranslationsMap(TranslationsMap<T> tm) {
		if (list == null) {
			list = new LinkedList<>();
		}
		list.add(tm);
		return tm;
	}

	public Translation() {
	}

	public Translation(String id) {
		this.id = id;
	}

	public Translation(Translation<T> that) {
		if (that != null) {
			this.id = that.id;
			this.desc = that.desc;
			this.list = that.list;
			this.route = that.route;
	
			if (that.attributes != null) {
				this.attributes = new HashMap<>(that.attributes);
			} else {
				this.attributes = new HashMap<>();
			}
		}
	}

	public String getDesc() {
		return desc;
	}

	public Translation<T> setDesc(String desc) {
		this.desc = desc;
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

	@JsonIgnore
	public void setAttributes(Map<String, String> attributes) {
		this.attributes = new HashMap<>(attributes);
	}

	@JsonIgnore
	public Translation<T> addAttribute(String key, String value) {
		attributes = (null != attributes) ? attributes : new HashMap<>();
		attributes.put(key, value);
		return this;
	}

	@JsonIgnore
	public String getAttribute(String key) {
		return attributes.get(key);
	}

	public T getRoute() {
		return route;
	}

	public Translation<T> setRoute(T route) {
		this.route = route;
		return this;
	}

}