package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Specifies the transfer target destination.
 *
 * <p>Provides multiple ways to identify the target: full SIP address,
 * SIP URI, user-part only, or account. Additional INVITE headers
 * can also be specified.
 */
public class Target implements Serializable {
	private static final long serialVersionUID = 1L;

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
		// Default constructor
	}

}