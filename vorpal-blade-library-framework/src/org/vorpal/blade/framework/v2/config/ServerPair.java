package org.vorpal.blade.framework.v2.config;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ //
//		"id", //
//		"description", //
		"primary", //
		"secondary" })
//@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class ServerPair {
//	private String id = null;
//	private String description = null;
	private String primary = null;
	private String secondary = null;

	public ServerPair() {
	}

//	public ServerPair(String id, String primary, String secondary) {
//		this.id = id;
//		this.primary = primary;
//		this.secondary = secondary;
//	}
//
//	public ServerPair(String id) {
//		this.id = id;
//	}

	public ServerPair(String primary, String secondary) {
		this.primary = primary;
		this.secondary = secondary;
	}

	public String getPrimary() {
		return primary;
	}

	public ServerPair setPrimary(String primary) {
		this.primary = primary;
		return this;
	}

	public String getSecondary() {
		return secondary;
	}

	public ServerPair setSecondary(String secondary) {
		this.secondary = secondary;
		return this;
	}

//	public String getId() {
//		return id;
//	}
//
//	public ServerPair setId(String id) {
//		this.id = id;
//		return this;
//	}
//
//	public String getDescription() {
//		return description;
//	}
//
//	public ServerPair setDescription(String description) {
//		this.description = description;
//		return this;
//	}

}
