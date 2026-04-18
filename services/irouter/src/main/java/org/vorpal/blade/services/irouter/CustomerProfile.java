package org.vorpal.blade.services.irouter;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Per-customer credentials used for the outbound screening/routing
/// REST call.
///
/// Carried as a `Translation<CustomerProfile>` payload in an
/// enrichment [org.vorpal.blade.framework.v3.configuration.adapters.TableAdapter].
/// When the table matches, each field is copied into the session
/// [org.vorpal.blade.framework.v3.configuration.Context] so a
/// downstream `RestAdapter` can use `${customerId}`, `${apiKey}`, and
/// `${baseUrl}` in its URL / auth / body templates.
@JsonPropertyOrder({ "customerId", "apiKey", "baseUrl" })
public class CustomerProfile implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Customer account identifier sent in the REST request")
	private String customerId;

	@JsonPropertyDescription("API key / bearer token authenticating the REST request")
	private String apiKey;

	@JsonPropertyDescription("Base URL of this customer's routing/screening REST endpoint")
	private String baseUrl;

	public CustomerProfile() {
	}

	public CustomerProfile(String customerId, String apiKey, String baseUrl) {
		this.customerId = customerId;
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
	}

	public String getCustomerId() { return customerId; }
	public void setCustomerId(String customerId) { this.customerId = customerId; }

	public String getApiKey() { return apiKey; }
	public void setApiKey(String apiKey) { this.apiKey = apiKey; }

	public String getBaseUrl() { return baseUrl; }
	public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
