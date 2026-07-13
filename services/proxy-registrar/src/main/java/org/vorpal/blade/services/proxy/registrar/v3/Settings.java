package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

@SchemaAbout(
		name = "Proxy Registrar",
		tagline = "SIP Registration & Location",
		description = "Processes SIP REGISTER requests and maintains the location database of "
				+ "contact bindings — registration, expiry, and lookups — and forwards calls to "
				+ "registered endpoints. Set session:passthru to drop out of the dialog after "
				+ "call setup (proxy behavior); leave it off to stay in the path as a B2BUA.")
public class Settings extends Configuration implements Serializable {
	private static final long serialVersionUID = -3362129920431974760L;

	/// Registrar config baseline version. The v3 shape (B2BUA fan-out; the
	/// proxy-only knobs removed) is generation 3: a config with no version
	/// reads as 3, and an explicitly-versioned file keeps its value. Stays
	/// read-only in the Configurator.
	@Override
	@JsonPropertyDescription("Config schema version (framework-managed). The v3 shape — B2BUA fan-out, no proxy knobs — is version 3.")
	@FormLayout(readOnly = true)
	public Integer getVersion() {
		return (version == null) ? 3 : version;
	}

	private String allowHeader;

	private Boolean proxyOnUnregistered;

	private Integer timeout;

	@JsonPropertyDescription("Specifies the SIP methods this container supports, used in the Allow header")
	public String getAllowHeader() {
		return allowHeader;
	}

	public void setAllowHeader(String allowHeader) {
		this.allowHeader = allowHeader;
	}

	@JsonPropertyDescription("Forward the request to its request URI instead of issuing a 404 Not Found error when no contact is registered")
	@JsonProperty(defaultValue = "false")
	public Boolean getProxyOnUnregistered() {
		return proxyOnUnregistered;
	}

	public void setProxyOnUnregistered(Boolean proxyOnUnregistered) {
		this.proxyOnUnregistered = proxyOnUnregistered;
	}

	@JsonPropertyDescription("Overall timeout in seconds for forked INVITE requests; outstanding requests are canceled and the caller receives a 408. Unset means no timer.")
	public Integer getTimeout() {
		return timeout;
	}

	public void setTimeout(Integer timeout) {
		this.timeout = timeout;
	}

}
