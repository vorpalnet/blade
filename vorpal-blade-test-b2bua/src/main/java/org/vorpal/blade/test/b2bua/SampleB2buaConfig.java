package org.vorpal.blade.test.b2bua;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.Address;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SampleB2buaConfig extends Configuration implements Serializable {
	@JsonPropertyDescription("The version of the configuration file.")
	public static final String version = "2.1";

	@JsonPropertyDescription("This is value1.")
	public String value1;

	@JsonPropertyDescription("This is value2.")
	public String value2;

}
