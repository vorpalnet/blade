package org.vorpal.blade.framework.v3.crud;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

/// Reads a map keyed by id, in the order written, as CRUD writes rules
/// ([RuleSet]) and connectors ([Pipeline]): or the earlier form, a list whose
/// items each name their `id` (one without gets `<noun>-N`). A `null` entry
/// is left out: how a cluster or server file removes one it inherits. Two
/// list items with one id (an earlier editor's Duplicate made them) are both
/// kept, the second renamed `<id>-2`, and a warning is logged.
public abstract class KeyedReader<T> extends JsonDeserializer<Map<String, T>> {

	private final String noun;
	private final Class<T> type;

	protected KeyedReader(String noun, Class<T> type) {
		this.noun = noun;
		this.type = type;
	}

	/// Gives `value` the id it is keyed by.
	protected abstract void setId(T value, String id);

	@Override
	public Map<String, T> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		JsonNode node = p.getCodec().readTree(p);
		Map<String, T> out = new LinkedHashMap<>();
		if (node == null || node.isNull()) return out;
		if (node.isObject()) {
			for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
				Map.Entry<String, JsonNode> e = it.next();
				if (e.getValue() == null || e.getValue().isNull()) continue;
				out.put(e.getKey(), read(p, e.getValue(), e.getKey()));
			}
			return out;
		}
		if (!node.isArray()) throw JsonMappingException.from(p, noun + "s must be an object keyed by " + noun + " id");
		int n = 0;
		for (JsonNode item : node) {
			n++;
			if (item == null || item.isNull()) continue;
			String id = item.path("id").asText("");
			if (id.isEmpty()) id = noun + "-" + n;
			if (out.containsKey(id)) {
				// An earlier editor's Duplicate kept the id: keep both, the
				// second as `<id>-2` (or the next free), so the file still loads
				// and nothing is lost; the next Save writes the new name.
				String renamed = id;
				for (int k = 2; out.containsKey(renamed); k++) renamed = id + "-" + k;
				org.vorpal.blade.framework.v2.logging.Logger log = org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger();
				if (log != null) log.warning("two " + noun + "s have the id \"" + id + "\"; the second is read as \"" + renamed + "\"");
				id = renamed;
			}
			out.put(id, read(p, item, id));
		}
		return out;
	}

	private T read(JsonParser p, JsonNode node, String id) throws IOException {
		T value = p.getCodec().treeToValue(node, type);
		if (value != null) setId(value, id);
		return value;
	}
}
