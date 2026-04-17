package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.adapters.Adapter;
import org.vorpal.blade.framework.v3.configuration.tables.RoutingTable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Top-level config for any service built on the iRouter pipeline.
///
/// - **`adapters`** — ordered list of [ProtocolAdapter]s. Each one
///   fetches a payload (or just consumes the SIP request) and runs
///   its [DataSelector]s to write attributes into the SIP session.
/// - **`tables`** — ordered list of [RoutingTable]s. The first one
///   whose `${keyExpression}` resolves to a known entry wins. The
///   matched entry is the **Treatment** (a `Map<String,String>`).
/// - **`defaultTreatment`** — used when no table matched.
///
/// No generics, no `Translation` wrapper. The whole framework deals
/// in plain `Map<String,String>` for treatments.
@JsonPropertyOrder({ "logging", "sessionExpiration", "keepAlive", "sessionSelectors", "analytics",
		"adapters", "tables", "defaultTreatment" })
public class IRouterConfig extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Ordered list of protocol adapters; each enriches the SIP session")
	public List<Adapter> adapters = new LinkedList<>();

	@JsonPropertyDescription("Ordered list of routing tables; first match wins")
	public List<RoutingTable> tables = new LinkedList<>();

	@JsonPropertyDescription("Treatment used when no table matches")
	public Map<String, String> defaultTreatment = new LinkedHashMap<>();

	public IRouterConfig() {
	}
}
