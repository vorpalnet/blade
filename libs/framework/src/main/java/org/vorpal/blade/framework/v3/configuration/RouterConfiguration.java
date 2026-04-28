package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.routing.Routing;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Top-level configuration for any service built on the BLADE v3 router
/// pipeline.
///
/// ## Two phases
///
/// 1. **Enrichment** — [#getPipeline] is an ordered list of
///    [Connector]s (SIP, REST, JDBC, LDAP, Map, Table). Each connector
///    writes values into the shared [Context] so that downstream
///    connectors' templates (`${var}`) resolve against what earlier
///    stages produced.
/// 2. **Decision** — [#getRouting] is a polymorphic
///    [Routing] (`table` or `direct` subtype) that reads the now-enriched
///    Context and produces a concrete
///    [org.vorpal.blade.framework.v3.configuration.routing.Route].
///
/// Separating the two phases keeps the mental model clean: "gather the
/// facts, then make the decision." Table connectors in the pipeline are
/// always pure enrichment; the routing decision is a single explicit
/// top-level field.
@JsonPropertyOrder({ "logging", "sessionExpiration", "pipeline", "routing" })
public class RouterConfiguration extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Ordered pipeline of enrichment connectors; each stage writes values into the shared Context")
	private List<Connector> pipeline = new LinkedList<>();

	@JsonPropertyDescription("Routing decision made after the pipeline completes; pick a type (table or direct)")
	private Routing routing;

	public RouterConfiguration() {
	}

	public List<Connector> getPipeline() {
		return pipeline;
	}

	public void setPipeline(List<Connector> pipeline) {
		this.pipeline = (pipeline != null) ? pipeline : new LinkedList<>();
	}

	public Routing getRouting() {
		return routing;
	}

	public void setRouting(Routing routing) {
		this.routing = routing;
	}
}
