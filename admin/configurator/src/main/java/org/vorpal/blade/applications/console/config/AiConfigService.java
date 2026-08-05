package org.vorpal.blade.applications.console.config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/// The AI config assistant's engine: takes an app's JSON Schema, the current
/// config, and an operator's plain-language instruction; asks Claude for a
/// full replacement config; validates it against the schema (one repair retry
/// on validation errors); and returns a proposal. It never writes or
/// publishes — the caller puts the proposal in front of the operator.
///
/// Secrets never leave the box: values in password-format schema fields and
/// values carrying the `{AES}`/`{3DES}`/`{CLEARTEXT}` credential prefixes are
/// replaced with `{REDACTED}` before the request, and restored from the
/// operator's own baseline afterward.
///
/// The prompt-assembly, redaction, and validation helpers are package-private
/// statics so they unit-test without a container or a network (same split as
/// admin/api's ApiHttp).
public class AiConfigService {

	private static final Logger logger = Logger.getLogger(AiConfigService.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	static final String REDACTED = "{REDACTED}";

	private static final String SYSTEM_PROMPT = "" //
			+ "You write configuration files for BLADE, a SIP servlet framework. "
			+ "You are given an application's JSON Schema, its current configuration, and an operator's instruction. "
			+ "Return the complete new configuration as a single JSON document.\n" //
			+ "Rules:\n" //
			+ "- Output ONLY the JSON document. No markdown fences, no commentary.\n"
			+ "- The document must conform to the JSON Schema. Use the schema's property descriptions to choose sensible values.\n"
			+ "- Change only what the instruction requires; preserve every other field of the current configuration.\n"
			+ "- Never alter, remove, or invent a value equal to \"" + REDACTED + "\" — copy it through unchanged.\n"
			+ "- If the instruction cannot be expressed in this schema, return the current configuration unchanged.";

	/// Runs the full generate flow. Returns a JSON string:
	/// `{"config": <object>, "valid": true|false, "errors": [ ... ]}`.
	/// Throws on missing key, refusal, unreachable API, or unparseable output.
	public static String generate(AiSettings ai, String schemaJson, String currentJson, String instruction)
			throws Exception {

		if (ai == null || !ai.isEnabled()) {
			throw new IllegalStateException("The AI config assistant is disabled. Enable it in the Configurator's own settings.");
		}
		String apiKey = plaintextKey(ai.getApiKey());
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("No API key configured. Set ai.apiKey in the Configurator's own settings.");
		}

		JsonNode schemaNode = mapper.readTree(schemaJson);
		JsonNode currentNode = (currentJson == null || currentJson.isBlank()) //
				? mapper.createObjectNode()
				: mapper.readTree(currentJson);

		JsonNode redacted = redactSecrets(currentNode.deepCopy(), schemaNode);
		String userPrompt = buildPrompt(schemaJson, mapper.writeValueAsString(redacted), instruction);

		String text = callClaude(ai, apiKey, userPrompt);
		JsonNode proposed = extractJson(text);
		List<String> errors = validate(schemaNode, proposed);

		if (!errors.isEmpty()) {
			logger.info("AI draft failed validation (" + errors.size() + " errors), retrying once");
			String repairPrompt = userPrompt + "\n\nYour previous draft was:\n" + mapper.writeValueAsString(proposed)
					+ "\n\nIt failed schema validation with these errors:\n- " + String.join("\n- ", errors)
					+ "\n\nReturn the corrected complete JSON document only.";
			text = callClaude(ai, apiKey, repairPrompt);
			proposed = extractJson(text);
			errors = validate(schemaNode, proposed);
		}

		restoreRedacted(currentNode, proposed);

		ObjectNode result = mapper.createObjectNode();
		result.set("config", proposed);
		result.put("valid", errors.isEmpty());
		ArrayNode errs = result.putArray("errors");
		errors.forEach(errs::add);
		return mapper.writeValueAsString(result);
	}

	// ------------------------------------------------------------------
	// Claude

	private static String callClaude(AiSettings ai, String apiKey, String userPrompt) {
		var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
		if (ai.getBaseUrl() != null && !ai.getBaseUrl().isBlank()) {
			builder.baseUrl(ai.getBaseUrl());
		}
		AnthropicClient client = builder.build();
		try {
			MessageCreateParams params = MessageCreateParams.builder() //
					.model(ai.getModel()) //
					.maxTokens(16000L) //
					.system(SYSTEM_PROMPT) //
					.addUserMessage(userPrompt) //
					.build();
			Message response = client.messages().create(params);

			if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
				throw new IllegalStateException("The model declined this request.");
			}
			if (response.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
				throw new IllegalStateException("The model's response was truncated (max tokens). Try a narrower instruction.");
			}

			StringBuilder sb = new StringBuilder();
			response.content().forEach(block -> block.text().ifPresent(t -> sb.append(t.text())));
			if (sb.length() == 0) {
				throw new IllegalStateException("The model returned no text (stop reason: " + response.stopReason() + ").");
			}
			return sb.toString();
		} finally {
			try {
				client.close();
			} catch (Exception e) {
				logger.log(Level.FINE, "error closing Anthropic client", e);
			}
		}
	}

	// ------------------------------------------------------------------
	// Pure helpers (unit-testable, no container, no network)

	static String buildPrompt(String schemaJson, String redactedConfigJson, String instruction) {
		return "JSON Schema:\n" + schemaJson //
				+ "\n\nCurrent configuration:\n" + redactedConfigJson //
				+ "\n\nInstruction:\n" + instruction;
	}

	/// Strips a `{CLEARTEXT}` prefix if present. Encrypted values are already
	/// decrypted by the framework before they reach the in-memory settings.
	static String plaintextKey(String value) {
		if (value == null) {
			return null;
		}
		return value.startsWith("{CLEARTEXT}") ? value.substring("{CLEARTEXT}".length()) : value;
	}

	static boolean isCredentialValue(String value) {
		return value != null
				&& (value.startsWith("{AES}") || value.startsWith("{3DES}") || value.startsWith("{CLEARTEXT}"));
	}

	/// Replaces secret values in `config` with `{REDACTED}`, in place, and
	/// returns it. A value is secret when its resolved subschema carries
	/// `"format": "password"` (the @FormLayout(password=true) marker), or when
	/// the value itself carries a credential prefix.
	static JsonNode redactSecrets(JsonNode config, JsonNode schemaRoot) {
		redactWalk(config, schemaRoot, schemaRoot);
		return config;
	}

	private static void redactWalk(JsonNode node, JsonNode subschema, JsonNode schemaRoot) {
		subschema = resolveRef(subschema, schemaRoot);
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				JsonNode childSchema = childSchema(subschema, field.getKey(), schemaRoot);
				if (field.getValue().isTextual() && isSecret(field.getValue().asText(), childSchema)) {
					obj.put(field.getKey(), REDACTED);
				} else {
					redactWalk(field.getValue(), childSchema, schemaRoot);
				}
			}
		} else if (node.isArray()) {
			JsonNode itemsSchema = (subschema != null) ? subschema.get("items") : null;
			for (JsonNode item : node) {
				redactWalk(item, itemsSchema, schemaRoot);
			}
		}
	}

	private static boolean isSecret(String value, JsonNode subschema) {
		JsonNode resolved = resolveRef(subschema, null);
		if (resolved != null && resolved.path("format").asText("").equals("password")) {
			return true;
		}
		return isCredentialValue(value);
	}

	private static JsonNode childSchema(JsonNode objectSchema, String fieldName, JsonNode schemaRoot) {
		if (objectSchema == null) {
			return null;
		}
		JsonNode properties = objectSchema.get("properties");
		if (properties != null && properties.has(fieldName)) {
			return resolveRef(properties.get(fieldName), schemaRoot);
		}
		JsonNode additional = objectSchema.get("additionalProperties");
		if (additional != null && additional.isObject()) {
			return resolveRef(additional, schemaRoot);
		}
		return null;
	}

	private static JsonNode resolveRef(JsonNode subschema, JsonNode schemaRoot) {
		if (subschema == null || schemaRoot == null || !subschema.has("$ref")) {
			return subschema;
		}
		String ref = subschema.get("$ref").asText();
		if (!ref.startsWith("#/")) {
			return subschema;
		}
		JsonNode resolved = schemaRoot.at(ref.substring(1));
		return resolved.isMissingNode() ? subschema : resolved;
	}

	/// Walks `proposed` in place; every string equal to `{REDACTED}` is
	/// restored from the same path in `original` when that path exists.
	static void restoreRedacted(JsonNode original, JsonNode proposed) {
		if (proposed.isObject()) {
			ObjectNode obj = (ObjectNode) proposed;
			Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				JsonNode originalChild = (original != null) ? original.get(field.getKey()) : null;
				if (field.getValue().isTextual() && REDACTED.equals(field.getValue().asText())) {
					if (originalChild != null && originalChild.isTextual()) {
						obj.set(field.getKey(), originalChild);
					}
				} else {
					restoreRedacted(originalChild, field.getValue());
				}
			}
		} else if (proposed.isArray()) {
			ArrayNode arr = (ArrayNode) proposed;
			for (int i = 0; i < arr.size(); i++) {
				JsonNode originalChild = (original != null && original.isArray() && i < original.size()) //
						? original.get(i)
						: null;
				if (arr.get(i).isTextual() && REDACTED.equals(arr.get(i).asText())) {
					if (originalChild != null && originalChild.isTextual()) {
						arr.set(i, originalChild);
					}
				} else {
					restoreRedacted(originalChild, arr.get(i));
				}
			}
		}
	}

	/// Same validation idiom as ValidationAPI: networknt against the app's
	/// generated schema. Returns human-readable messages, empty when valid.
	static List<String> validate(JsonNode schemaNode, JsonNode configNode) {
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);
		JsonSchema schema = factory.getSchema(schemaNode);
		Set<ValidationMessage> messages = schema.validate(configNode);
		List<String> errors = new ArrayList<>();
		messages.forEach(m -> errors.add(m.getMessage()));
		return errors;
	}

	/// Extracts the JSON document from the model's text: tolerates markdown
	/// fences and any stray prose around the outermost braces.
	static JsonNode extractJson(String text) throws Exception {
		String trimmed = text.trim();
		int start = trimmed.indexOf('{');
		int end = trimmed.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new IllegalStateException("The model's response contained no JSON document.");
		}
		return mapper.readTree(trimmed.substring(start, end + 1));
	}
}
