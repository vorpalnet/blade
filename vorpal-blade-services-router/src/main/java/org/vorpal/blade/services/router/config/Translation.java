package org.vorpal.blade.services.router.config;

import java.util.LinkedList;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class Translation {
	public String id;
	public String description;
	public LinkedList<TranslationsMap> list;
	public String requestUri;
	public String[] route;
	public String[] routeBack;
	public String[] routeFinal;

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getId() {
		return id;
	}

	public Translation setId(String id) {
		this.id = id;
		return this;
	}

	public LinkedList<TranslationsMap> getList() {
		return list;
	}

	public Translation setList(LinkedList<TranslationsMap> list) {
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

	public String[] getRoute() {
		return route;
	}

	public Translation setRoute(String[] route) {
		this.route = route;
		return this;
	}

	public String[] getRouteBack() {
		return routeBack;
	}

	public Translation setRouteBack(String[] routeBack) {
		this.routeBack = routeBack;
		return this;
	}

	public String[] getRouteFinal() {
		return routeFinal;
	}

	public Translation setRouteFinal(String[] routeFinal) {
		this.routeFinal = routeFinal;
		return this;
	}

}