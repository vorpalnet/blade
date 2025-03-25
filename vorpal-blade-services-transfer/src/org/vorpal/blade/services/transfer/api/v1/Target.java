package org.vorpal.blade.services.transfer.api.v1;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public class Target implements Serializable {
	@Schema(description = "Full target SIP address", defaultValue = "Carol <sip:carol@vorpal.net>", nullable = true)
	public String sipAddress;

	@Schema(description = "Full target SIP URI", defaultValue = "sip:carol@vorpal.net", nullable = true)
	public String sipUri;

	@Schema(description = "Just the SIP user-part (phone number). The rest of the URI will be created from the target's address", defaultValue = "carol", nullable = true)
	public String user;

	@Schema(description = "Just SIP account. The rest of the URI will be created from the target's address", defaultValue = "carol@vorpal.org", nullable = true)
	public String account;

	@Schema(description = "List of additional INVITE headers", nullable = true)
	public List<Header> inviteHeaders;

	public Target() {
		// do nothing
	}
}