package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Per-application settings for publishing onto the BLADE event bus — the
/// `"events"` block of an app's configuration, sitting beside `"analytics"`.
///
/// **Why this is per-app and opt-in.** The event *catalog* is domain-wide: it
/// says what event types exist and what they carry, and it is a contract. This
/// is the other thing — whether *this* application opens a connection to the bus
/// at all. Standing a JMS connection up in every deployed app whether or not it
/// publishes would cost one connection per app per node for nothing, so it is
/// off until asked for, exactly as
/// [org.vorpal.blade.framework.v2.analytics.Analytics#isEnabled] is.
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
@JsonPropertyOrder({ "enabled", "connectionFactoryJndi", "destinationJndi", "source" })
public class EventBusSettings implements Serializable {

	private static final long serialVersionUID = 1L;

	private Boolean enabled = false;
	private String connectionFactoryJndi = EventBus.CONNECTION_FACTORY_JNDI;
	private String destinationJndi = EventBus.TOPIC_JNDI;
	private String source;

	@JsonPropertyDescription("Whether this application publishes to the event bus. Off by default: an app that does not publish should not hold a JMS connection. Switching on 'analytics' turns this on implicitly, because analytics publishes through this same bus.")
	public Boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = (enabled == null) ? Boolean.FALSE : enabled;
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

	@JsonPropertyDescription("CloudEvents 'source' stamped on events this application publishes. Leave empty to derive it from the application name.")
	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}
}
