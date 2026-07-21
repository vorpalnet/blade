package org.vorpal.blade.proto.gateway;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One virtual gateway: a single trunk registration hosted by the gateway servlet.
///
/// Multiple virtual gateways run in one SIP servlet — multi‑homed / multi‑DID /
/// multi‑tenant. Each advertises its own **Contact IP** ({@link #getContactHost()}):
/// the registrar (and inbound calls) are steered to that local address, selected from
/// the container's SIP outbound interfaces. The {@link RegistrationStyle} decides HOW
/// this gateway stays registered (digest REGISTER, IP‑auth, …).
@JsonPropertyOrder({ "name", "contactHost", "contactPort", "transport", "registrarDomain", "outboundProxy", "style" })
public class VirtualGateway implements Serializable {
	private static final long serialVersionUID = 1L;

	private String name;
	private String contactHost;
	private int contactPort = 5060;
	private String transport = "udp";
	private String registrarDomain;
	private String outboundProxy;
	private RegistrationStyle style;

	@JsonPropertyDescription("Human‑readable name for this virtual gateway (also used in logs and the SIP display name).")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@JsonPropertyDescription("The local IP address advertised in Contact and used to select the outbound SIP "
			+ "interface. Must be one of the container's configured SIP network channels.")
	public String getContactHost() {
		return contactHost;
	}

	public void setContactHost(String contactHost) {
		this.contactHost = contactHost;
	}

	@JsonPropertyDescription("Local SIP port for the Contact / outbound interface (default 5060).")
	public int getContactPort() {
		return contactPort;
	}

	public void setContactPort(int contactPort) {
		this.contactPort = contactPort;
	}

	@JsonPropertyDescription("Transport for this gateway: udp | tcp | tls.")
	public String getTransport() {
		return transport;
	}

	public void setTransport(String transport) {
		this.transport = transport;
	}

	@JsonPropertyDescription("The carrier's registrar / SIP domain, e.g. us-east-nj.sip.flowroute.com.")
	public String getRegistrarDomain() {
		return registrarDomain;
	}

	public void setRegistrarDomain(String registrarDomain) {
		this.registrarDomain = registrarDomain;
	}

	@JsonPropertyDescription("Optional outbound proxy (host[:port]) to route requests through instead of the registrar directly.")
	public String getOutboundProxy() {
		return outboundProxy;
	}

	public void setOutboundProxy(String outboundProxy) {
		this.outboundProxy = outboundProxy;
	}

	@JsonPropertyDescription("How this gateway stays registered with the carrier (registration technique).")
	public RegistrationStyle getStyle() {
		return style;
	}

	public void setStyle(RegistrationStyle style) {
		this.style = style;
	}

	/// The Request‑URI to send an outbound call for `number` out this trunk:
	/// `sip:<number>@<registrarDomain>;transport=<transport>`.
	public String trunkRequestUri(String number) {
		StringBuilder sb = new StringBuilder("sip:");
		if (number != null && !number.isEmpty()) {
			sb.append(number).append('@');
		}
		sb.append(registrarDomain);
		if (transport != null && !transport.isEmpty()) {
			sb.append(";transport=").append(transport);
		}
		return sb.toString();
	}

	/// True if a container SIP outbound interface `host`/`port` satisfies this gateway's
	/// Contact binding. Port 0 on either side is a wildcard.
	public boolean matchesInterface(String host, int port) {
		if (contactHost == null || host == null) {
			return false;
		}
		boolean hostMatch = contactHost.equalsIgnoreCase(host);
		boolean portMatch = contactPort <= 0 || port <= 0 || contactPort == port;
		return hostMatch && portMatch;
	}
}
