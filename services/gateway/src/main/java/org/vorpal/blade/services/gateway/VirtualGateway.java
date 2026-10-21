package org.vorpal.blade.services.gateway;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One virtual gateway: a single outbound SIP trunk.
///
/// The gateway app is **outbound only**. FSMAR routes an outbound INVITE here with
/// `;vgw=<name>` naming the trunk, and {@link GatewaySipServlet#callStarted} rewrites the
/// dialog onto it: Request-URI to {@link #getRegistrarDomain()}, From to the
/// {@link RegistrationStyle}'s outbound identity. The {@link RegistrationStyle} also
/// decides how the trunk stays registered (digest REGISTER, ip-auth, ...).
///
/// Inbound calls do not come through here. A carrier's INVITE lands on the engine and
/// FSMAR routes it straight to the answering app, so a trunk needs no inbound
/// identification (no arrival interface, no source allowlist). The only local-interface
/// concern that remains is outbound source selection on a multi-homed engine, and it is
/// optional: see {@link #getOutboundInterface()}.
@JsonPropertyOrder({ "name", "transport", "registrarDomain", "dialPrefix", "callerId", "outboundInterface", "style" })
public class VirtualGateway implements Serializable {
	private static final long serialVersionUID = 1L;

	private String name;
	private String transport = "udp";
	private String registrarDomain;
	private String dialPrefix;
	private String callerId;
	private String outboundInterface;
	private RegistrationStyle style;

	@JsonPropertyDescription("Human-readable name for this trunk (used in logs and the SIP display name), and the value FSMAR names in the ;vgw= route parameter for an outbound call.")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@JsonPropertyDescription("Transport for this trunk: udp | tcp | tls.")
	public String getTransport() {
		return transport;
	}

	public void setTransport(String transport) {
		this.transport = transport;
	}

	@JsonPropertyDescription("The carrier's registrar / SIP domain, e.g. sip.carrier.example.com.")
	public String getRegistrarDomain() {
		return registrarDomain;
	}

	public void setRegistrarDomain(String registrarDomain) {
		this.registrarDomain = registrarDomain;
	}

	@JsonPropertyDescription("Optional: text the carrier wants in front of the dialed number on an outbound call, "
			+ "such as an account tech prefix and its separator (12345678*). Sent as "
			+ "sip:<dialPrefix><number>@<registrarDomain>.")
	public String getDialPrefix() {
		return dialPrefix;
	}

	public void setDialPrefix(String dialPrefix) {
		this.dialPrefix = dialPrefix;
	}

	@JsonPropertyDescription("Optional: the number to present as the From user on outbound calls, usually one of "
			+ "the account's own phone numbers. Unset presents the registration style's identity (for a digest "
			+ "trunk, its userId), which some carriers reject as caller ID.")
	public String getCallerId() {
		return callerId;
	}

	public void setCallerId(String callerId) {
		this.callerId = callerId;
	}

	@JsonPropertyDescription("Optional, multi-homed engines only: the local SIP interface to originate this trunk's "
			+ "REGISTER and outbound INVITEs from, named by the interface's advertised host (its channel listen or "
			+ "public address). Leave unset on a single-interface engine; the container's own interface is used, and "
			+ "its public-address sets the Contact.")
	public String getOutboundInterface() {
		return outboundInterface;
	}

	public void setOutboundInterface(String outboundInterface) {
		this.outboundInterface = outboundInterface;
	}

	@JsonPropertyDescription("How this trunk stays registered with the carrier (registration technique).")
	public RegistrationStyle getStyle() {
		return style;
	}

	public void setStyle(RegistrationStyle style) {
		this.style = style;
	}

	/// The Request-URI to send an outbound call for `number` out this trunk:
	/// `sip:<dialPrefix><number>@<registrarDomain>;transport=<transport>`.
	public String trunkRequestUri(String number) {
		StringBuilder sb = new StringBuilder("sip:");
		if (number != null && !number.isEmpty()) {
			if (dialPrefix != null) {
				sb.append(dialPrefix);
			}
			sb.append(number).append('@');
		}
		sb.append(registrarDomain);
		if (transport != null && !transport.isEmpty()) {
			sb.append(";transport=").append(transport);
		}
		return sb.toString();
	}
}
