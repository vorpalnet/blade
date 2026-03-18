package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

/**
 * Generic name-value pair container.
 * @param <Name> the type of the name
 * @param <Value> the type of the value
 */
public class NameValuePair<Name, Value> implements Serializable {
	private static final long serialVersionUID = 1L;

	private Name name;
	private Value value;

	public NameValuePair() {
		// do nothing;
	}

	public NameValuePair(Name name, Value value) {
		this.name = name;
		this.value = value;
	}

	public Name getName() {
		return name;
	}

	public void setName(Name name) {
		this.name = name;
	}

	public Value getValue() {
		return value;
	}

	public void setValue(Value value) {
		this.value = value;
	}

}
