package org.vorpal.blade.services.irouter;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Defines where to route a call and what headers to apply.
///
/// This is the treatment payload for the irouter's
/// [RouterConfiguration]. When a [Selector] matches an inbound
/// request and a [TranslationTable] lookup succeeds, the resulting
/// `RoutingTreatment` tells the B2BUA where to send the outbound leg.
public class RoutingTreatment implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Destination SIP URI for the outbound INVITE")
	public String requestUri;

	@JsonPropertyDescription("Headers to add or set on the outbound INVITE")
	public Map<String, String> headers;

	public RoutingTreatment() {
	}

	public RoutingTreatment(String requestUri) {
		this.requestUri = requestUri;
	}

	public RoutingTreatment(String requestUri, Map<String, String> headers) {
		this.requestUri = requestUri;
		this.headers = headers;
	}
}
