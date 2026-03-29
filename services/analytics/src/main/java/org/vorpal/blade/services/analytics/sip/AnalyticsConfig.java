package org.vorpal.blade.services.analytics.sip;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

@JsonSchemaTitle(value = "Analytics")
public class AnalyticsConfig extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	public Integer healthCheckInterval;
	public String healthCheckSql;

}
