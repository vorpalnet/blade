package org.vorpal.blade.proto.gateway;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Configurator‑managed settings for the SIP trunk gateway: the list of
/// {@link VirtualGateway}s this one servlet hosts. Each virtual gateway is an
/// independent trunk registration with its own Contact IP, registrar, credentials,
/// and refresh timer.
public class GatewaySettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private List<VirtualGateway> gateways = new ArrayList<>();

	@JsonPropertyDescription("The virtual gateways this servlet hosts — each an independent SIP trunk "
			+ "registration with its own Contact IP address.")
	public List<VirtualGateway> getGateways() {
		return gateways;
	}

	public void setGateways(List<VirtualGateway> gateways) {
		this.gateways = gateways;
	}
}
