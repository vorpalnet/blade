package org.vorpal.blade.framework.v3.configuration.translations;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/// A single entry in a table connector's translations map.
///
/// A Translation "is" its payload: an arbitrary bag of string key/value
/// pairs. When the parent table's lookup matches, every entry in that bag is
/// written into the session [org.vorpal.blade.framework.v3.configuration.Context]
/// so that later pipeline stages (REST bodies, JDBC templates, routing
/// keys, …) can interpolate the values with `${name}`.
///
/// Jackson `@JsonAnySetter` / `@JsonAnyGetter` let the bag serialize inline —
/// every JSON property flows into the extras map on deserialization and back
/// out as a top-level property on serialization. The retired `description`
/// field is ignored (folded into Configuration.notes) so older configs that
/// still carry it keep loading.
@JsonIgnoreProperties("description")
public class Translation implements Serializable {
	private static final long serialVersionUID = 1L;

	private final Map<String, String> extras = new LinkedHashMap<>();

	public Translation() {
	}

	/// Serializes every entry as a top-level property.
	@JsonAnyGetter
	public Map<String, String> getExtras() {
		return extras;
	}

	/// Captures any JSON property not otherwise recognized. Values are
	/// stringified (Jackson will hand us the raw scalar).
	@JsonAnySetter
	public void putExtra(String name, Object value) {
		if (name == null) return;
		extras.put(name, (value == null) ? null : value.toString());
	}

	/// Convenience for programmatic construction (e.g. `IRouterConfigSample`).
	public Translation put(String name, String value) {
		if (name != null) extras.put(name, value);
		return this;
	}
}
