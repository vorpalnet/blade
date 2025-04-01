package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class TranslationOld implements Serializable {
	private static final long serialVersionUID = 1L;
	private String id;
	private String description;
	private List<TranslationsMap> list;
	private String requestUri;
	private String[] route;
	private String[] routeBack;
	private String[] routeFinal;
	private Map<String, Object> attributes;

	public TranslationOld() {
	}

	public TranslationOld(String id) {
		this.id = id;
	}

	public TranslationOld(TranslationOld that) {
		if (that != null) {
			this.id = that.id;
			this.description = that.description;
			this.list = that.list;
			this.requestUri = that.requestUri;
			this.route = that.route;
			this.routeBack = that.routeBack;
			this.routeFinal = that.routeFinal;

			if (attributes != null) {
				this.attributes = new HashMap<>(that.attributes);
			} else {
				this.attributes = new HashMap<>();
			}

		}
	}

	public String getDescription() {
		return description;
	}

	public TranslationOld setDescription(String description) {
		this.description = description;
		return this;
	}

	public String getId() {
		return id;
	}

	public TranslationOld setId(String id) {
		this.id = id;
		return this;
	}

	public List<TranslationsMap> getList() {
		return list;
	}

	public TranslationOld setList(List<TranslationsMap> maps) {
		this.list = maps;
		return this;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public TranslationOld setRequestUri(String requestUri) {
		this.requestUri = requestUri;
		return this;
	}

	public String[] getRoute() {
		return route;
	}

	public TranslationOld setRoute(String[] route) {
		this.route = route;
		return this;
	}

	public String[] getRouteBack() {
		return routeBack;
	}

	public TranslationOld setRouteBack(String[] routeBack) {
		this.routeBack = routeBack;
		return this;
	}

	public String[] getRouteFinal() {
		return routeFinal;
	}

	public TranslationOld setRouteFinal(String[] routeFinal) {
		this.routeFinal = routeFinal;
		return this;
	}

	public Map<String, Object> getAttributes() {
		return attributes;
	}

	public void setAttributes(Map<String, String> attributes) {
		this.attributes = new HashMap<>(attributes);
	}

	@JsonIgnore
	public TranslationOld addAttribute(String key, Object value) {
		attributes = (null != attributes) ? attributes : new HashMap<>();
		attributes.put(key, value);
		return this;
	}

	@JsonIgnore
	public Object getAttribute(String key) {
		return (null != attributes) ? attributes.get(key) : null;
	}

}