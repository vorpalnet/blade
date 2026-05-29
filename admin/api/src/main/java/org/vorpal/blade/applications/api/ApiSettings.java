package org.vorpal.blade.applications.api;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the API Explorer.
///
/// Beyond the inherited launcher metadata (`name` / `tagline` / `description`),
/// it carries one field: the **engine-tier base URL** where deployed services
/// publish their OpenAPI documents. That tier is a different host:port from the
/// AdminServer this app runs on, and the value must be reachable from the
/// operator's browser (so it must be the externally-visible address — behind a
/// load balancer or NAT that can differ from the JMX `ListenAddress`).
public class ApiSettings extends Configuration {
	private static final long serialVersionUID = 1L;

	protected String engineBaseUrl;

	@JsonPropertyDescription("Base URL of the engine tier where services publish OpenAPI documents, e.g. \"http://sip.example.com:8001\". Used to build <engineBaseUrl>/<contextRoot>/resources/openapi.json. Must be reachable from the operator's browser for live \"try it\" requests.")
	public String getEngineBaseUrl() {
		return engineBaseUrl;
	}

	public ApiSettings setEngineBaseUrl(String engineBaseUrl) {
		this.engineBaseUrl = engineBaseUrl;
		return this;
	}
}
