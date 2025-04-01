package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.callflow.Callflow;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Translation implements Serializable {
	private static final long serialVersionUID = 1L;
	private String id;
	private String description;
	private List<TranslationsMap> list;
	private String requestUri;
//	private String[] route;
//	private String[] routeBack;
//	private String[] routeFinal;
	private Map<String, String> attributes;

	public Translation() {
	}

	public Translation(String id) {
		this.id = id;
	}

	public Translation(Translation that) {
		if (that != null) {
			this.id = that.id;
			this.description = that.description;
			this.list = that.list;
			this.requestUri = that.requestUri;

			this.attributes = new HashMap<>();
			if (that.attributes != null && that.attributes.size() > 0) {
				this.attributes.putAll(that.attributes);
			}

		}
	}

	public String getDescription() {
		return description;
	}

//	public String[] getRoute() {
//		return route;
//	}
//
//	public Translation2 setRoute(String[] route) {
//		this.route = route;
//		return this;
//	}
//
//	public String[] getRouteBack() {
//		return routeBack;
//	}
//
//	public Translation2 setRouteBack(String[] routeBack) {
//		this.routeBack = routeBack;
//		return this;
//	}
//
//	public String[] getRouteFinal() {
//		return routeFinal;
//	}
//
//	public Translation2 setRouteFinal(String[] routeFinal) {
//		this.routeFinal = routeFinal;
//		return this;
//	}
//
//	public Map<String, Object> getAttributes() {
//		return attributes;
//	}

	public Translation setAttributes(Map<String, String> attributes) {
		this.attributes = new HashMap<>(attributes);
		return this;
	}

	public String getId() {
		return id;
	}

	public Translation setId(String id) {
		this.id = id;
		return this;
	}

	public List<TranslationsMap> getList() {
		return list;
	}

	public Translation setList(List<TranslationsMap> list) {
		this.list = list;
		return this;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public Translation setRequestUri(String requestUri) {
		this.requestUri = requestUri;
		return this;
	}

	public Map<String, String> getAttributes() {
		return attributes;
	}

	public Translation setDescription(String description) {
		this.description = description;
		return this;
	}

	@JsonIgnore
	public Translation addAttribute(String key, String value) {
		attributes = (null != attributes) ? attributes : new HashMap<>();
		attributes.put(key, value);
		return this;
	}

	@JsonIgnore
	public String getAttribute(String key) {
		return (null != attributes) ? attributes.get(key) : null;
	}

}