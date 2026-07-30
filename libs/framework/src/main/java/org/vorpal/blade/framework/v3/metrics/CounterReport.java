package org.vorpal.blade.framework.v3.metrics;

import java.io.Serializable;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// JSON snapshot of one [Counter], carried in a [MetricsReport].
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "name", "description", "label", "total", "values" })
public class CounterReport implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	private String description;
	private String label;
	private long total;
	private Map<String, Long> values;

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

	/// The label dimension, or null when the counter has none.
	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public long getTotal() {
		return total;
	}

	public void setTotal(long total) {
		this.total = total;
	}

	/// Per-label values, or null for an unlabeled counter.
	public Map<String, Long> getValues() {
		return values;
	}

	public void setValues(Map<String, Long> values) {
		this.values = values;
	}
}
