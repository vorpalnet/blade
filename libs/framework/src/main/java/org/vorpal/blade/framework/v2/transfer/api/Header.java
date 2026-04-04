package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents a SIP header name-value pair.
 *
 * <p>Used to specify additional headers for INVITE or REFER requests
 * in transfer operations.
 */
public class Header implements Serializable {
	private static final long serialVersionUID = 1L;

	@Schema(description = "Name of SIP header", defaultValue = "X-Importance", nullable = false)
	public String name;

	@Schema(description = "Value of SIP header", defaultValue = "low", nullable = false)
	public String value;

	public Header() {
		// Default constructor
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
