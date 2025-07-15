package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;

public class DialogKey implements Serializable {
	private static final long serialVersionUID = 1L;

	@Schema(description = "dialog (SipSession) attribute name", defaultValue = "userAgent", nullable = true)
	public String name;

	@Schema(description = "dialog (SipSession) attribute value", defaultValue = "caller", nullable = true)
	public String value;

}
