package org.vorpal.blade.services.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventSourceGenerator;
import org.vorpal.blade.framework.v3.events.EventType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/// Checks a published payload against the schema its event type declares.
///
/// This is the half of the catalog that has teeth. A declaration that only fed
/// code generation would drift the moment a producer changed shape without
/// regenerating; validating at the ingress is what makes the declaration a
/// contract rather than documentation.
///
/// **Schemas are compiled once per catalog, not once per event.** Generating and
/// compiling a JSON Schema costs far more than checking a small payload against
/// a compiled one, and this sits on the ingress path. The compiled set is
/// rebuilt only when the catalog object itself changes — `SettingsManager` hands
/// out a fresh instance on every reload, so an identity comparison is exactly
/// the right trigger and needs no version counter or listener.
public class EventValidator {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonSchemaFactory FACTORY = JsonSchemaFactory
			.getInstance(SpecVersion.VersionFlag.V202012);

	/// The catalog the compiled schemas were built from, compared by identity.
	private EventCatalog compiledFor;
	private Map<String, JsonSchema> compiled = new HashMap<>();

	/// The outcome of validating one payload.
	public static final class Result {

		private final boolean valid;
		private final List<String> problems;

		private Result(boolean valid, List<String> problems) {
			this.valid = valid;
			this.problems = problems;
		}

		/// True when the payload satisfied its type's schema, or when there was
		/// no schema to check it against.
		public boolean isValid() {
			return valid;
		}

		/// One line per failing constraint, naming the field — the thing an
		/// operator actually needs in a 400 body or a log line.
		public List<String> getProblems() {
			return problems;
		}

		/// The problems as a single line, for logging.
		public String summary() {
			return String.join("; ", problems);
		}
	}

	private static final Result VALID = new Result(true, new ArrayList<String>());

	/// Validate an event's payload against the schema its type declares.
	///
	/// Returns valid when the catalog does not declare the type, or declares it
	/// with no fields — deciding what to do about an unknown type is
	/// [EventCatalog#isRejectUnknownTypes]'s job, not this one's.
	///
	/// @param catalog the current catalog
	/// @param event   the event being published
	/// @return the outcome; never null
	public synchronized Result validate(EventCatalog catalog, CloudEvent event) {
		if (catalog == null || event == null || event.getType() == null) {
			return VALID;
		}
		EventType declaration = catalog.findType(event.getType());
		if (declaration == null || declaration.fieldsOrEmpty().isEmpty()) {
			return VALID;
		}

		JsonSchema schema = schemaFor(catalog, declaration);
		if (schema == null) {
			return VALID;
		}

		JsonNode data = (event.getData() == null) ? MissingNode.getInstance() : event.getData();
		Set<ValidationMessage> errors = schema.validate(data);
		if (errors.isEmpty()) {
			return VALID;
		}

		List<String> problems = new ArrayList<>(errors.size());
		for (ValidationMessage message : errors) {
			problems.add(message.getMessage());
		}
		return new Result(false, problems);
	}

	/// The compiled schema for a type, rebuilding the whole set when the catalog
	/// instance has changed.
	private JsonSchema schemaFor(EventCatalog catalog, EventType declaration) {
		if (catalog != compiledFor) {
			compiled = compileAll(catalog);
			compiledFor = catalog;
		}
		return compiled.get(declaration.getType());
	}

	private static Map<String, JsonSchema> compileAll(EventCatalog catalog) {
		Map<String, JsonSchema> schemas = new HashMap<>();
		for (EventType declaration : catalog.typesOrEmpty()) {
			if (declaration.getType() == null || declaration.fieldsOrEmpty().isEmpty()) {
				continue;
			}
			JsonNode schemaNode = EventSourceGenerator.schema(declaration, MAPPER);
			schemas.put(declaration.getType(), FACTORY.getSchema(schemaNode));
		}
		return schemas;
	}
}
