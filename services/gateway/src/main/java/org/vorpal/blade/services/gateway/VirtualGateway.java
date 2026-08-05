package org.vorpal.blade.services.gateway;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One virtual gateway: a single trunk registration hosted by the gateway servlet.
///
/// Multiple virtual gateways run in one SIP servlet — multi‑homed / multi‑DID /
/// multi‑tenant. Each advertises its own **Contact IP** ({@link #getContactHost()}):
/// the registrar (and inbound calls) are steered to that local address, selected from
/// the container's SIP outbound interfaces. The {@link RegistrationStyle} decides HOW
/// this gateway stays registered (digest REGISTER, IP‑auth, …).
///
/// A trunk describes **both directions**:
///
///  - {@link #getRegistrarDomain()} — where we send calls **to**, via
///    {@link #trunkRequestUri(String)}.
///  - {@link #getSourceHosts()} — where the carrier signals **from**, via
///    {@link #matchesSource(String)}.
///
/// Inbound, {@link #matchesInterface(String, int)} identifies which trunk a call
/// arrived on (its Contact IP is the address the carrier was told to use), and
/// `sourceHosts` then confirms the call really came from that carrier.
@JsonPropertyOrder({ "name", "contactHost", "contactPort", "transport", "registrarDomain", "sourceHosts",
		"outboundProxy", "style" })
public class VirtualGateway implements Serializable {
	private static final long serialVersionUID = 1L;

	private String name;
	private String contactHost;
	private int contactPort = 5060;
	private String transport = "udp";
	private String registrarDomain;
	private List<String> sourceHosts = new ArrayList<>();
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

	@JsonPropertyDescription("Addresses the carrier signals inbound calls FROM — bare IPs or CIDR blocks, "
			+ "e.g. [\"203.0.113.0/24\", \"198.51.100.7\"]. The inbound counterpart to registrarDomain: "
			+ "an INVITE arriving on this trunk's Contact IP is accepted only if its source is listed here. "
			+ "Leave empty to accept any source on that interface.")
	public List<String> getSourceHosts() {
		return sourceHosts;
	}

	public void setSourceHosts(List<String> sourceHosts) {
		this.sourceHosts = (sourceHosts != null) ? sourceHosts : new ArrayList<>();
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

	/// True if `ip` is one of this trunk's {@link #getSourceHosts()} — a bare address match or
	/// CIDR containment. An empty `sourceHosts` accepts anything (the trunk is identified by the
	/// Contact interface alone).
	///
	/// Same subnet math as the FSMAR `insubnet` operator
	/// (`v3.configuration.expressions.Expression`): the `ipaddress` library, so any prefix
	/// boundary works and IPv4/IPv6 are both handled. A malformed entry is skipped, never thrown —
	/// one bad line in the config must not reject live calls.
	public boolean matchesSource(String ip) {
		if (sourceHosts == null || sourceHosts.isEmpty()) {
			return true;
		}
		if (ip == null) {
			return false;
		}
		inet.ipaddr.IPAddress source = new inet.ipaddr.IPAddressString(ip).getAddress();
		if (source == null) {
			return false;
		}
		for (String entry : sourceHosts) {
			if (entry == null || entry.isEmpty()) {
				continue;
			}
			try {
				inet.ipaddr.IPAddress block = new inet.ipaddr.IPAddressString(entry.trim()).getAddress();
				if (block != null && block.toPrefixBlock().contains(source)) {
					return true;
				}
			} catch (RuntimeException malformed) {
				continue;
			}
		}
		return false;
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
