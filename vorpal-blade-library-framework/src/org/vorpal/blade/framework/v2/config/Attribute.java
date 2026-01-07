package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

/**
 * Simple name-value pair representing a single attribute.
 */
public class Attribute implements Serializable {
	private static final long serialVersionUID = 1L;

	public String name;
	public String value;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

}
