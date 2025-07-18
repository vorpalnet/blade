package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;

public class Header implements Serializable {
	@Schema(description = "Name of SIP header", defaultValue = "X-Importance", nullable = false)
	public String name;

	@Schema(description = "Value of SIP header", defaultValue = "low", nullable = false)
	public String value;

	public Header() {
		// do nothing
	}

	public String getName() {
		return name;
	}

	public Header setName(String name) {
		this.name = name;
		return this;
	}

	public String getValue() {
		return value;
	}

	public Header setValue(String value) {
		this.value = value;
		return this;
	}

}
