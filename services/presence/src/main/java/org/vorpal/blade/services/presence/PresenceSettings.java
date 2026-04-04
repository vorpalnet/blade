package org.vorpal.blade.services.presence;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class PresenceSettings implements Serializable {

	@JsonPropertyDescription("General-purpose configuration setting for the presence service.")
	@JsonProperty(defaultValue = "setting1")
	public String setting1 = "setting1";

}
