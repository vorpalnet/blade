package org.vorpal.blade.test.b2bua;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SchemaTitle;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@SchemaTitle("B2BUA Configuration")
public class TestB2buaConfiguration extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Your name")
	public String traveler;

	@JsonPropertyDescription("Your quest")
	public String quest;

	@JsonPropertyDescription("Your favorite color")
	public String color;

}
