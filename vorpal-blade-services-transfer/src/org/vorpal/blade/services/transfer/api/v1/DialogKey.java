package org.vorpal.blade.services.transfer.api.v1;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;

public class DialogKey implements Serializable {
	private static final long serialVersionUID = 1L;

	@Schema(description = "dialog (SipSession) attribute name", defaultValue = "userAgent", nullable = true)
	public String name;

	@Schema(description = "dialog (SipSession) attribute value", defaultValue = "caller", nullable = true)
	public String value;

}
