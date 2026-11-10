package org.vorpal.blade.framework.v3.crud;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.connectors.Connector;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The CRUD pipeline as written: connectors keyed by id, in the order they
/// run.
///
/// ```json
/// "pipeline": {
///   "sip":            { "type": "sip", "selectors": [ … ] },
///   "rule-selection": { "type": "table", "tables": [ … ] }
/// }
/// ```
///
/// Keys for the same reason as [RuleSet]'s rules: the settings loader merges
/// objects key by key but appends a list, so with keys a cluster or server
/// file can change one connector by naming it, and a new key runs after the
/// inherited ones. [Connector] keeps its own `id` (iRouter lists its
/// connectors), so [Writer] leaves it out of each entry and [Reader] puts the
/// key back. [Reader] also reads the earlier form, a list of connectors each
/// naming its `id`.
public final class Pipeline {

	private Pipeline() {
	}

	/// Sets each connector's id to its key.
	static Map<String, Connector> keyed(Map<String, Connector> pipeline) {
		Map<String, Connector> out = (pipeline != null) ? pipeline : new LinkedHashMap<>();
		out.forEach((id, c) -> { if (c != null) c.setId(id); });
		return out;
	}

	/// Writes each connector without its `id`, which is the key.
	public static class Writer extends JsonSerializer<Map<String, Connector>> {
		@Override
		public void serialize(Map<String, Connector> pipeline, JsonGenerator gen, SerializerProvider provider)
				throws IOException {
			gen.writeStartObject();
			for (Map.Entry<String, Connector> e : pipeline.entrySet()) {
				gen.writeFieldName(e.getKey());
				JsonNode node = ((com.fasterxml.jackson.databind.ObjectMapper) gen.getCodec()).valueToTree(e.getValue());
				if (node instanceof ObjectNode) ((ObjectNode) node).remove("id");
				gen.writeTree(node);
			}
			gen.writeEndObject();
		}
	}

	/// Reads connectors keyed by id, or the earlier list form ([KeyedReader]).
	public static class Reader extends KeyedReader<Connector> {
		public Reader() {
			super("connector", Connector.class);
		}

		@Override
		protected void setId(Connector connector, String id) {
			connector.setId(id);
		}
	}
}
