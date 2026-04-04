package org.vorpal.blade.services.analytics.sip;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

@JsonSchemaTitle(value = "Analytics")
public class AnalyticsConfig extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Interval in seconds between database health check queries")
	@JsonProperty(defaultValue = "30")
	public Integer healthCheckInterval;

	@JsonPropertyDescription("SQL query used to verify database connectivity")
	@JsonProperty(defaultValue = "SELECT 1 FROM DUAL")
	public String healthCheckSql;

}
