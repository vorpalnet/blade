package org.vorpal.blade.services.proxy.balancer.config;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v2.config.FormUriPreview;

/// One downstream destination, defined ONCE in the top-level `endpoints`
/// registry and referenced by name from any tier of any plan. The registry
/// key is the endpoint's name — the stable identity that health tracking and
/// the dashboard use, so editing the address doesn't reset the endpoint's
/// history.
///
/// The address is entered as structured fields, not a free-typed SIP URI —
/// an administrator can't malform a scheme, forget a `transport=` param, or
/// leave a dangling colon. [#getUri] assembles the actual SIP URI string
/// from the parts on demand. `@FormUriPreview` gives the configurator a live
/// ✓/✕ syntax-validity badge for it.
@FormUriPreview
public class Endpoint implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The URI scheme.
	public enum Scheme {
		sip, sips
	}

	/// The `transport=` URI parameter.
	public enum Transport {
		udp, tcp
	}

	private Scheme scheme = Scheme.sip;
	private Transport transport = Transport.udp;
	private String host;
	private Integer port;
	private String user;
	private String uriParams;
	private Integer weight = 1;
	private Boolean enabled = true;
	private Boolean ping = true;
	private String site;

	public Endpoint() {
	}

	public Endpoint(String host) {
		this.host = host;
	}

	@JsonPropertyDescription("URI scheme")
	@JsonProperty(defaultValue = "sip", required = true)
	public Scheme getScheme() {
		return scheme;
	}

	public Endpoint setScheme(Scheme scheme) {
		this.scheme = scheme;
		return this;
	}

	@JsonPropertyDescription("Transport carried in the URI's transport= parameter")
	@JsonProperty(defaultValue = "udp", required = true)
	public Transport getTransport() {
		return transport;
	}

	public Endpoint setTransport(Transport transport) {
		this.transport = transport;
		return this;
	}

	@JsonPropertyDescription("Hostname or IP address of the endpoint")
	@JsonProperty(required = true)
	public String getHost() {
		return host;
	}

	public Endpoint setHost(String host) {
		this.host = host;
		return this;
	}

	@JsonPropertyDescription("Port; leave blank for the SIP default (5060, or 5061 for sips)")
	public Integer getPort() {
		return port;
	}

	public Endpoint setPort(Integer port) {
		this.port = port;
		return this;
	}

	@JsonPropertyDescription("Optional URI user part; most SIP trunks/SBCs ignore this")
	public String getUser() {
		return user;
	}

	public Endpoint setUser(String user) {
		this.user = user;
		return this;
	}

	@JsonPropertyDescription("Advanced: literal extra URI parameters appended verbatim, e.g. for a test-harness "
			+ "target ('status=501;delay=2'). Leave blank for a real endpoint.")
	public String getUriParams() {
		return uriParams;
	}

	public Endpoint setUriParams(String uriParams) {
		this.uriParams = uriParams;
		return this;
	}

	@JsonPropertyDescription("Relative weight for the 'weighted' strategy; higher receives proportionally more first attempts")
	@JsonProperty(defaultValue = "1", required = true)
	public Integer getWeight() {
		return weight;
	}

	public Endpoint setWeight(Integer weight) {
		this.weight = weight;
		return this;
	}

	@JsonPropertyDescription("Set false to drain: no new calls are routed here, but the endpoint stays configured and monitored")
	@JsonProperty(defaultValue = "true", required = true)
	public Boolean getEnabled() {
		return enabled;
	}

	public Endpoint setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@JsonPropertyDescription("Set false to exclude this endpoint from the OPTIONS ping cycle (health then comes from live traffic only)")
	@JsonProperty(defaultValue = "true", required = true)
	public Boolean getPing() {
		return ping;
	}

	public Endpoint setPing(Boolean ping) {
		this.ping = ping;
		return this;
	}

	@JsonPropertyDescription("Optional site reference (a key in the top-level 'sites' registry); places this endpoint on the map view")
	public String getSite() {
		return site;
	}

	public Endpoint setSite(String site) {
		this.site = site;
		return this;
	}

	/// Assembles the SIP URI from the structured fields — the only place that
	/// builds the wire form. NOT a config property: derived, so editing e.g.
	/// `host` alone changes the effective URI without touching a stored
	/// string.
	@JsonIgnore
	public String getUri() {
		StringBuilder sb = new StringBuilder();
		sb.append(scheme == null ? Scheme.sip : scheme).append(':');
		if (user != null && !user.isBlank()) {
			sb.append(user).append('@');
		}
		sb.append(host);
		if (port != null) {
			sb.append(':').append(port);
		}
		sb.append(";transport=").append(transport == null ? Transport.udp : transport);
		if (uriParams != null && !uriParams.isBlank()) {
			sb.append(';').append(uriParams);
		}
		return sb.toString();
	}

	/// Convenience for routing: drained means not offered new calls. NOT a
	/// config property — `enabled` is the stored field; without the ignore,
	/// Jackson serializes a phantom "drained" that breaks reload.
	@JsonIgnore
	public boolean isDrained() {
		return Boolean.FALSE.equals(enabled);
	}

}
