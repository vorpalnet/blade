package org.vorpal.blade.services.events;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Event Bus ingress app, edited through the Configurator like
/// every other BLADE app's config.
///
/// The JMS names are intentionally NOT here: the topic and connection-factory
/// JNDI names are fixed framework constants
/// ([org.vorpal.blade.framework.v3.events.EventBus]) because the consumer MDB's
/// `@MessageDriven(mappedName = ...)` is a compile-time constant and must match
/// the publisher — making them operator-editable would let the two drift apart.
/// What belongs here is policy: the default `source` stamped on events that
/// arrive over HTTP without one.
@SchemaAbout(
		name = "Event Bus",
		tagline = "CloudEvents pub/sub for BLADE apps",
		description = "Ingress for the BLADE v3 event bus. Accepts CloudEvents 1.0 over HTTP and republishes them onto a JMS topic that any number of BLADE apps can subscribe to. This is how an in-network producer such as the Gumball attendant hands a completed request — 'schedule an appointment' — to a downstream calendar app, decoupled and durably.")
public class EventBusSettings extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	private String source = "/blade/events";

	@JsonPropertyDescription("CloudEvents 'source' stamped on inbound events that arrive without one. Identifies this bus ingress as the origin.")
	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = (source == null || source.isEmpty()) ? "/blade/events" : source;
	}
}
