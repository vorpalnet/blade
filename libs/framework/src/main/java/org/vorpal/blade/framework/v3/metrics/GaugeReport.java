package org.vorpal.blade.framework.v3.metrics;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// JSON snapshot of one [Gauge], carried in a [MetricsReport].
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "name", "description", "value" })
public class GaugeReport implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	private String description;
	private long value;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public long getValue() {
		return value;
	}

	public void setValue(long value) {
		this.value = value;
	}
}
