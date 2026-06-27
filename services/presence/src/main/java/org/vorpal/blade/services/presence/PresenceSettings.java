package org.vorpal.blade.services.presence;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

@SchemaAbout(
		name = "Presence",
		tagline = "SIP/SIMPLE Presence & Subscriptions",
		description = "Tracks user presence state and manages SUBSCRIBE/NOTIFY so watchers "
				+ "receive presence updates, implementing the SIP/SIMPLE event model.")
public class PresenceSettings implements Serializable {

	@JsonPropertyDescription("General-purpose configuration setting for the presence service.")
	@JsonProperty(defaultValue = "setting1")
	public String setting1 = "setting1";

}
