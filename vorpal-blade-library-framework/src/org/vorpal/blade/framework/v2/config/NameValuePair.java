package org.vorpal.blade.framework.v2.config;

public class NameValuePair<Name, Value> {

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
