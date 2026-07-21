package org.vorpal.blade.proto.gateway;

/// `type: "ip-auth"` — the carrier authenticates by source IP allowlist and needs **no
/// REGISTER** (Twilio Elastic SIP Trunking, most BYOC). Nothing to keep alive; the
/// gateway is reachable at its Contact IP and just exchanges INVITEs. {@link #newRegistrar}
/// returns `null`.
public class IpAuthStyle extends RegistrationStyle {
	private static final long serialVersionUID = 1L;

	@Override
	public TrunkRegistrar newRegistrar(VirtualGateway gateway) {
		return null; // IP‑authenticated trunk — no registration
	}

	@Override
	public String outboundIdentity() {
		return null; // identified by source IP; leave the caller's From unchanged
	}
}
