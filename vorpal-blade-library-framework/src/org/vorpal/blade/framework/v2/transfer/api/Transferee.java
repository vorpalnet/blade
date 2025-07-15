package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;

public class Transferee implements Serializable {
	@Schema(description = "X-Vorpal-Dialog", defaultValue = "BA5E", nullable = true)
	public String dialog; // x-vorpal-dialog

	@Schema(description = "User part of the SIP URI", defaultValue = "18165551234", nullable = true)
	public String user;

	@Schema(description = "User and host parts of the SIP URI", defaultValue = "alice@vorpal.org", nullable = true)
	public String account;

	@Schema(description = "Full SIP address", defaultValue = "Alice <sip@alice@vorpal.org>", nullable = true)
	public String sipAddress;

	@Schema(description = "Full SIP URI", defaultValue = "sip@alice@vorpal.org", nullable = true)
	public String sipUri;

	@Schema(description = "SipSession attribute", nullable = true)
	public Header dialogAttribute;

	public Transferee() {
		// do nothing;
	}
}
