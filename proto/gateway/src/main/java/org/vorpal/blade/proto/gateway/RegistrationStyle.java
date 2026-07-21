package org.vorpal.blade.proto.gateway;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/// The pluggable **registration technique** for a {@link VirtualGateway} — carriers
/// don't all keep a trunk alive the same way (digest REGISTER, IP‑auth, OPTIONS
/// keep‑alive, per‑carrier quirks). A Jackson‑polymorphic config type (the same idiom
/// as `v3.configuration.selectors.Selector` / `connectors.Connector`): the JSON `type`
/// discriminator selects the concrete style, and **the style carries the behavior** —
/// {@link #newRegistrar(VirtualGateway)} produces the {@link TrunkRegistrar} (a
/// Callflow) that does the work.
///
/// Adding a carrier = one `@JsonSubTypes.Type` line + a subclass.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
		defaultImpl = RegisterDigestStyle.class)
@JsonSubTypes({
		@JsonSubTypes.Type(value = RegisterDigestStyle.class, name = "register-digest"),
		@JsonSubTypes.Type(value = IpAuthStyle.class, name = "ip-auth")
})
public abstract class RegistrationStyle implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Produce the registrar that keeps `gateway` registered, or `null` if this carrier
	/// needs no REGISTER (e.g. IP‑authenticated trunks). Called once per virtual gateway
	/// at startup; the returned registrar owns the register / refresh‑timer / de‑register
	/// lifecycle.
	public abstract TrunkRegistrar newRegistrar(VirtualGateway gateway);

	/// The trunk identity (SIP user) to present in the From of an OUTBOUND call, or `null`
	/// to leave the caller's From unchanged (e.g. IP‑auth trunks that identify by source IP).
	public abstract String outboundIdentity();
}
