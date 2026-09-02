package org.vorpal.blade.services.gateway;

import java.net.InetSocketAddress;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

/// The runtime contract a {@link RegistrationStyle} produces to keep one
/// {@link VirtualGateway} registered: a client (UAC) callflow that owns the
/// register → refresh → de‑register lifecycle. Extends {@link ClientCallflow}
/// (no‑op `process()`; this originates traffic, it doesn't handle inbound requests).
public abstract class TrunkRegistrar extends ClientCallflow {
	private static final long serialVersionUID = 1L;

	/// The trunk this registrar keeps registered. Held on the base so the settings
	/// manager can match a running registrar to a config entry (by name) and compare
	/// the old settings against the new ones on a config reload.
	protected final VirtualGateway gateway;

	protected TrunkRegistrar(VirtualGateway gateway) {
		this.gateway = gateway;
	}

	/// The trunk this registrar was built from.
	public VirtualGateway getGateway() {
		return gateway;
	}

	/// Begin registration and keep‑alive. `outboundInterface` is the local address the
	/// gateway's Contact IP resolved to (from the container's SIP outbound interfaces),
	/// or `null` to use the container default.
	public abstract void start(InetSocketAddress outboundInterface) throws Exception;

	/// De‑register (REGISTER Expires:0) and cancel the keep‑alive timer. Best effort.
	public abstract void stop();
}
