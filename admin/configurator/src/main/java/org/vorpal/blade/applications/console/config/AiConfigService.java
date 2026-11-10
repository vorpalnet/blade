package org.vorpal.blade.applications.console.config;

import org.vorpal.blade.framework.v3.ai.AiConfig;
import org.vorpal.blade.framework.v3.ai.AiSettings;

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
			+ "You are a chat beside the BLADE Configurator, the editor for a SIP servlet framework's configuration files. "
			+ "You are given an application's JSON Schema, its current configuration, the conversation so far, and the "
			+ "operator's message. They may ask for a change, or ask a question about the configuration.\n" //
			+ "Answer with one JSON object and nothing else: {\"reply\": \"...\", \"config\": {...} or null}.\n" //
			+ "- reply: for a change, one or two plain sentences saying what you changed; for a question, the answer in a "
			+ "few plain sentences. Write for an engineer: short, no headings.\n"
			+ "- config: for a change, the complete new configuration; for a question, or a request this schema cannot "
			+ "express, null (and say why in reply).\n"
			+ "Rules for config:\n" //
			+ "- It must conform to the JSON Schema. Use the schema's property descriptions to choose sensible values.\n"
			+ "- Change only what the message requires; preserve every other field of the current configuration.\n"
			+ "- Never alter, remove, or invent a value equal to \"" + REDACTED + "\"; copy it through unchanged.\n"
			+ "- The current configuration already holds anything you proposed earlier that the operator kept.";

	/// The AI settings to use: the domain's shared `blade-ai.json`, which
	/// every admin editor reads ([AiConfig]), when it exists; else the
	/// Configurator's own `ai` section, read in process (the Configuration
	/// MBean's JSON masks the key). Publishes blade-ai's schema, so the
	/// Configurator lists it.
	static AiSettings settings() {
		AiConfig.publishSchema();
		if (AiConfig.exists()) return AiConfig.load();
		ConfiguratorSettings settings = ConfigurationMonitorStartup.currentSettings();
		return (settings != null && settings.getAi() != null) ? settings.getAi() : new AiSettings();
	}

	/// [#generate(AiSettings, String, String, String, String)] with no conversation.
	public static String generate(AiSettings ai, String schemaJson, String currentJson, String instruction)
			throws Exception {
		return generate(ai, schemaJson, currentJson, instruction, null);
	}

	/// Answers one message of the Configurator's chat. `historyJson` is the
	/// earlier turns, `[{role: user|assistant, text}]`, or null. Returns a
	/// JSON string `{"reply": "...", "config": <object> | null, "valid":
	/// true|false, "errors": [ ... ]}`: `config` null for a question.
	/// Throws on missing key, refusal, unreachable API, or unparseable output.
	public static String generate(AiSettings ai, String schemaJson, String currentJson, String instruction,
			String historyJson) throws Exception {

		if (ai == null || !ai.isEnabled()) {
			throw new IllegalStateException("The AI assistant is off; turn it on in blade-ai.");
		}
		String apiKey = plaintextKey(ai.getApiKey());
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("No API key; set apiKey in blade-ai.");
		}

		JsonNode schemaNode = mapper.readTree(schemaJson);
		JsonNode currentNode = (currentJson == null || currentJson.isBlank()) //
				? mapper.createObjectNode()
				: mapper.readTree(currentJson);

		JsonNode redacted = redactSecrets(currentNode.deepCopy(), schemaNode);
		String userPrompt = buildPrompt(schemaJson, mapper.writeValueAsString(redacted), conversation(historyJson), instruction);

		String text = callClaude(ai, apiKey, userPrompt);
		JsonNode answer = extractJson(text);
		String reply = replyOf(answer);
		JsonNode proposed = configOf(answer);
		if (proposed == null) {
			ObjectNode result = mapper.createObjectNode();
			result.put("reply", reply != null ? reply : "");
			result.putNull("config");
			result.put("valid", true);
			result.putArray("errors");
			return mapper.writeValueAsString(result);
		}
		List<String> errors = validate(schemaNode, proposed);

		if (!errors.isEmpty()) {
			logger.info("AI draft failed validation (" + errors.size() + " errors), retrying once");
			String repairPrompt = userPrompt + "\n\nYour previous draft was:\n" + mapper.writeValueAsString(proposed)
					+ "\n\nIt failed schema validation with these errors:\n- " + String.join("\n- ", errors)
					+ "\n\nAnswer again, the same way, with the config corrected.";
			text = callClaude(ai, apiKey, repairPrompt);
			answer = extractJson(text);
			JsonNode repaired = configOf(answer);
			if (repaired != null) {
				proposed = repaired;
				if (replyOf(answer) != null) reply = replyOf(answer);
			}
			errors = validate(schemaNode, proposed);
		}

		restoreRedacted(currentNode, proposed);

		ObjectNode result = mapper.createObjectNode();
		result.put("reply", reply != null ? reply : "");
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
		return buildPrompt(schemaJson, redactedConfigJson, "", instruction);
	}

	static String buildPrompt(String schemaJson, String redactedConfigJson, String conversation, String instruction) {
		return "JSON Schema:\n" + schemaJson //
				+ "\n\nCurrent configuration:\n" + redactedConfigJson //
				+ (conversation.isEmpty() ? "" : "\n\nThe conversation so far:\n" + conversation)
				+ "\n\nOperator:\n" + instruction;
	}

	/// The last dozen earlier turns, one per line; enough for a follow-up.
	static String conversation(String historyJson) {
		if (historyJson == null || historyJson.isBlank()) return "";
		try {
			JsonNode turns = mapper.readTree(historyJson);
			if (!turns.isArray()) return "";
			StringBuilder b = new StringBuilder();
			for (int i = Math.max(0, turns.size() - 12); i < turns.size(); i++) {
				JsonNode t = turns.get(i);
				String text = t.path("text").asText("").trim();
				if (text.isEmpty()) continue;
				b.append("assistant".equals(t.path("role").asText()) ? "You: " : "Operator: ").append(text).append("\n");
			}
			return b.toString();
		} catch (Exception e) {
			return "";
		}
	}

	/// The answer's reply, or null. A bare document (no `reply`/`config`
	/// keys) is an older-style answer: the configuration itself.
	static String replyOf(JsonNode answer) {
		return (answer != null && answer.has("reply") && answer.get("reply").isTextual()) ? answer.get("reply").asText() : null;
	}

	static JsonNode configOf(JsonNode answer) {
		if (answer == null) return null;
		if (!answer.has("reply") && !answer.has("config")) return answer;
		JsonNode c = answer.get("config");
		return (c != null && c.isObject()) ? c : null;
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
		// Any {SCHEME} prefix marks a credential: {CLEARTEXT} awaiting encryption, or an
		// encrypted form. WebLogic 14.1 emits {AES256}, so match the shape, not a fixed
		// list, or an {AES256} secret slips past unredacted.
		return value != null && value.matches("^\\{[A-Za-z0-9]+\\}.*");
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
