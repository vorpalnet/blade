package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Per-application settings for publishing onto the BLADE event bus — the
/// `"events"` block of an app's configuration, sitting beside `"analytics"`.
///
/// **Three settings, and unset is the default.** `true` publishes, and an
/// unreachable bus is an error. `false` publishes nothing. Unset publishes when
/// the domain's JMS bus is there and says nothing when it is not, so a domain
/// that never paid for the bus gets no events and no errors. See
/// [EventBus#reconcile], the one place this is read.
///
/// **Why an app needs its own publisher.** The framework jar ships inside each
/// WAR (`libs/shared` carries third-party jars only), so `EventBus`'s registry
/// of publishers is per-WAR static state. A publisher registered by
/// `services/events` is invisible to every other application. Without a
/// publisher of its own, an app calling [EventBus#publish] gets nothing and no
/// error — the silent no-op the catalog exists to abolish, reappearing one layer
/// down.
///
/// The JNDI names default to the constants in [EventBus] and only need setting
/// on a domain whose destinations were provisioned under different names.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "enabled", "connectionFactoryJndi", "destinationJndi", "providerUrl", "source" })
public class EventBusSettings implements Serializable {

	private static final long serialVersionUID = 1L;

	/// Null is the default: publish when the bus is there, quietly skip when it
	/// is not.
	private Boolean enabled;
	private String connectionFactoryJndi = EventBus.CONNECTION_FACTORY_JNDI;
	private String destinationJndi = EventBus.TOPIC_JNDI;
	private String source;
	private String providerUrl;

	@JsonPropertyDescription("Whether this application publishes to the event bus, including the call lifecycle events the framework emits. Leave unset to publish only when the domain's JMS bus is provisioned and say nothing when it is not; true to require it (an unreachable bus is then an error); false to never publish.")
	public Boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	@JsonPropertyDescription("JNDI name of the connection factory the bus publishes through. Defaults to the framework constant; set it only on a domain provisioned under different names.")
	public String getConnectionFactoryJndi() {
		return connectionFactoryJndi;
	}

	public void setConnectionFactoryJndi(String connectionFactoryJndi) {
		this.connectionFactoryJndi = (connectionFactoryJndi == null || connectionFactoryJndi.isEmpty())
				? EventBus.CONNECTION_FACTORY_JNDI
				: connectionFactoryJndi;
	}

	@JsonPropertyDescription("JNDI name of the destination this application publishes to. Defaults to the framework constant.")
	public String getDestinationJndi() {
		return destinationJndi;
	}

	public void setDestinationJndi(String destinationJndi) {
		this.destinationJndi = (destinationJndi == null || destinationJndi.isEmpty()) ? EventBus.TOPIC_JNDI
				: destinationJndi;
	}

	@JsonPropertyDescription("Where the bus's JNDI names are looked up, when not in this server's own tree: a provider URL on the engine cluster, e.g. t3://engine0.example:8001 (several comma-separated). Leave empty: an engine finds the bus in its own tree, and an application on the AdminServer finds the running engines that host it by itself. Set it only to override that.")
	public String getProviderUrl() {
		return providerUrl;
	}

	public void setProviderUrl(String providerUrl) {
		this.providerUrl = providerUrl;
	}

	@JsonPropertyDescription("CloudEvents 'source' stamped on events this application publishes. Leave empty to derive it from the application name.")
	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}
}
