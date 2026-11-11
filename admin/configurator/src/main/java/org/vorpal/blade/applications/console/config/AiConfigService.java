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

	/// The model answered, but with nothing usable (declined, truncated, no
	/// JSON): the request, not the service, is the problem. Asked again as
	/// is, it fails again.
	static final class Unanswered extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		Unanswered(String message) {
			super(message);
		}
	}


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

	/// [#generate(AiSettings, String, String, String, String, boolean)] with no conversation, for a domain file.
	public static String generate(AiSettings ai, String schemaJson, String currentJson, String instruction)
			throws Exception {
		return generate(ai, schemaJson, currentJson, instruction, null, false);
	}

	/// For a domain file.
	public static String generate(AiSettings ai, String schemaJson, String currentJson, String instruction,
			String historyJson) throws Exception {
		return generate(ai, schemaJson, currentJson, instruction, historyJson, false);
	}

	/// `schema` with no `required` lists: an overlay (a cluster or server
	/// file) holds only what differs from the domain file, so a field the
	/// whole document requires is rightly absent from it.
	static JsonNode withoutRequired(JsonNode schema) {
		JsonNode copy = schema.deepCopy();
		stripRequired(copy);
		return copy;
	}

	private static void stripRequired(JsonNode node) {
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			if (obj.path("required").isArray()) obj.remove("required");
			obj.elements().forEachRemaining(AiConfigService::stripRequired);
		} else if (node.isArray()) {
			node.elements().forEachRemaining(AiConfigService::stripRequired);
		}
	}

	/// Answers one message of the Configurator's chat. `historyJson` is the
	/// earlier turns, `[{role: user|assistant, text}]`, or null. Returns a
	/// JSON string `{"reply": "...", "config": <object> | null, "valid":
	/// true|false, "errors": [ ... ]}`: `config` null for a question.
	/// Throws on missing key, refusal, unreachable API, or unparseable output.
	public static String generate(AiSettings ai, String schemaJson, String currentJson, String instruction,
			String historyJson, boolean overlay) throws Exception {

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
		if (overlay) {
			userPrompt += "\n\nThis file is a cluster or server overlay: it holds only what differs from the domain "
					+ "file (maps merge by key, lists are appended to the domain's). Keep it partial; do not copy "
					+ "the domain's settings into it.";
		}
		// An overlay is checked without `required`: it is partial on purpose.
		JsonNode checkSchema = overlay ? withoutRequired(schemaNode) : schemaNode;

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
		List<String> errors = validate(checkSchema, withSecrets(currentNode, proposed));

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
			errors = validate(checkSchema, withSecrets(currentNode, proposed));
		}

		restoreRedacted(currentNode, proposed);
		for (String at : unrestoredSecrets(proposed, "")) {
			errors.add(at + ": a secret here could not be carried over; enter it again before saving");
		}

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
				throw new Unanswered("The model declined this request.");
			}
			if (response.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
				throw new Unanswered("The model's response was truncated (max tokens). Try a narrower instruction.");
			}

			StringBuilder sb = new StringBuilder();
			response.content().forEach(block -> block.text().ifPresent(t -> sb.append(t.text())));
			if (sb.length() == 0) {
				throw new Unanswered("The model returned no text (stop reason: " + response.stopReason() + ").");
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
			// A name/value pair naming a secret ({"name": "X-Api-Key", "value": "..."}):
			// the value is the secret, though neither key says so.
			boolean namedSecret = false;
			for (String n : new String[] { "name", "key", "header", "field" }) {
				if (obj.path(n).isTextual() && secretName(obj.get(n).asText())) namedSecret = true;
			}
			Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				JsonNode childSchema = childSchema(subschema, field.getKey(), schemaRoot);
				if (field.getValue().isTextual()
						&& (isSecret(field.getValue().asText(), childSchema) || secretName(field.getKey())
								|| (namedSecret && "value".equals(field.getKey())))) {
					obj.put(field.getKey(), REDACTED);
				} else if (field.getValue().isContainerNode() && secretName(field.getKey())) {
					redactAll(field.getValue());   // "tokens": [...], "credentials": {...}: every text in it
				} else {
					redactWalk(field.getValue(), childSchema, schemaRoot);
				}
			}
		} else if (node.isArray()) {
			JsonNode itemsSchema = (subschema != null) ? subschema.get("items") : null;
			ArrayNode arr = (ArrayNode) node;
			for (int i = 0; i < arr.size(); i++) {
				JsonNode item = arr.get(i);
				// A secret that is itself a list item (a list of passwords or keys).
				if (item.isTextual() && isSecret(item.asText(), resolveRef(itemsSchema, schemaRoot))) {
					arr.set(i, REDACTED);
				} else {
					redactWalk(item, itemsSchema, schemaRoot);
				}
			}
		}
	}

	/// A copy of `proposed` with the secrets the model saw as {REDACTED} put
	/// back, for validation only: a `pattern` on a password must judge the
	/// password, not the marker. The model is never shown this copy.
	private static JsonNode withSecrets(JsonNode current, JsonNode proposed) {
		JsonNode copy = proposed.deepCopy();
		restoreRedacted(current, copy);
		return copy;
	}

	/// Every text value under `node` replaced by {REDACTED}.
	private static void redactAll(JsonNode node) {
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			List<String> keys = new ArrayList<>();
			obj.fieldNames().forEachRemaining(keys::add);
			for (String k : keys) {
				if (obj.get(k).isTextual()) obj.put(k, REDACTED);
				else redactAll(obj.get(k));
			}
		} else if (node.isArray()) {
			ArrayNode arr = (ArrayNode) node;
			for (int i = 0; i < arr.size(); i++) {
				if (arr.get(i).isTextual()) arr.set(i, REDACTED);
				else redactAll(arr.get(i));
			}
		}
	}

	/// A key that names a secret whatever its schema says: a header map's
	/// "Authorization", a "token" or "apiKey" in a free-form map. Not one that
	/// names where a secret goes or how it is handled (`tokenUrl`,
	/// `secretName`, `passwordFile`, `tokenTtl`): the model needs those to
	/// answer and to edit them.
	static boolean secretName(String key) {
		if (key == null) return false;
		String k = key.toLowerCase(java.util.Locale.ROOT);
		if (k.matches("pwd|pass|psk")) return true;
		// keystorePass, trustStorePass, passphrase, preSharedKey (not bypass, compass)
		if (k.matches(".*((?<!by|com)pass|passphrase|psk|presharedkey)")) return true;
		return k.matches(".*(authorization|token|secret|password|passwd|api[-_]?key|cookie|credential|private[-_]?key).*")
				&& !k.matches(".*(url|uri|endpoint|path|file|name|type|header|enabled|ttl|expiry|expiration|timeout|seconds|length|policy|scheme|algorithm)s?");
	}

	private static boolean isSecret(String value, JsonNode subschema) {
		JsonNode resolved = resolveRef(subschema, null);
		if (resolved != null && resolved.path("format").asText("").equals("password")) {
			return true;
		}
		return isCredentialValue(value) || carriesCredential(value);
	}

	/// A value with a credential inside ordinary text: a URL's password
	/// (`https://user:pass@host`), a connection string's `password=`, an
	/// HTTP `Bearer` or `Basic` credential.
	static boolean carriesCredential(String value) {
		if (value == null) return false;
		return value.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*://[^/@\\s:]+:[^@\\s/]+@.*")
				// No "//": jdbc:oracle:thin:scott/tiger@db, sip:alice:pw@host
				|| value.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:([A-Za-z0-9+.-]+:)*[^/@\\s:;]+[:/][^@\\s/:;]+@.*")
				// A header written out: "X-Api-Key: abc", "Authorization: Token xyz"
				|| value.matches("(?is)^\\s*[a-z0-9-]*(authorization|token|api-?key|secret|cookie|password)[a-z0-9-]*\\s*:\\s*\\S.*")
				|| value.matches("(?is).*(^|[;&?\\s])(password|pwd|passwd)=.*")
				|| value.matches("(?is)^(authorization:\\s*)?(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}\\s*$");
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
		// victools' variant wrapper: {"$ref": base, "properties": {"type": {const}}}.
		// The wrapper's own properties pin the type; the fields are on the base.
		JsonNode ref = objectSchema.get("$ref");
		if (ref != null && ref.isTextual()) {
			JsonNode base = resolveRef(objectSchema, schemaRoot);
			if (base != null && base != objectSchema) {
				JsonNode found = childSchema(base, fieldName, schemaRoot);
				if (found != null) return found;
			}
		}
		// A polymorphic object (a pipeline connector, an authentication): the
		// field's schema in whichever variant has it, a password one first, so
		// a plain password in any variant is redacted too.
		for (String key : new String[] { "anyOf", "oneOf" }) {
			JsonNode variants = objectSchema.get(key);
			if (variants == null || !variants.isArray()) continue;
			JsonNode found = null;
			for (JsonNode v : variants) {
				JsonNode child = childSchema(resolveRef(v, schemaRoot), fieldName, schemaRoot);
				if (child == null) continue;
				if (resolveRef(child, schemaRoot).path("format").asText("").equals("password")) return child;
				if (found == null) found = child;
			}
			if (found != null) return found;
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
			// Each item's original first: one claimed by two proposed items (an
			// edit made one equal to another apart from secrets) gives its
			// secret to neither, or one would silently get the other's
			// credential. Left {REDACTED}: reported, and Save asks for them.
			JsonNode[] from = new JsonNode[arr.size()];
			java.util.IdentityHashMap<JsonNode, Integer> claims = new java.util.IdentityHashMap<>();
			for (int i = 0; i < arr.size(); i++) {
				from[i] = originalItem(original, arr.get(i), i, arr.size());
				if (from[i] != null) claims.merge(from[i], 1, Integer::sum);
			}
			for (int i = 0; i < arr.size(); i++) {
				JsonNode originalChild = (from[i] != null && claims.get(from[i]) == 1) ? from[i] : null;
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

	/// The original item a proposed list item came from: the one with the same
	/// `id` (or `name`), else the one at the same place when the list kept its
	/// length. By place alone, removing the first endpoint would hand the
	/// second endpoint's secret the first one's value.
	private static JsonNode originalItem(JsonNode original, JsonNode item, int i, int proposedSize) {
		if (original == null || !original.isArray()) return null;
		// The one original that is this item apart from its secrets: neither an
		// id shared by two items (connector ids need not be unique) nor a
		// position (the list may have been reordered) can tell which it was.
		JsonNode match = null;
		for (JsonNode o : original) {
			if (sameButSecrets(o, item)) {
				if (match != null) { match = null; break; }   // two candidates: neither
				match = o;
			}
		}
		if (match != null) return match;
		// A bare secret string in a list ("keys": ["{AES}a", "{AES}b"]): every
		// text item matches it. In a list of the same length, the one at its
		// place; else none (reported).
		if (item.isTextual() && REDACTED.equals(item.asText()) && proposedSize == original.size()
				&& original.get(i) != null && original.get(i).isTextual()) {
			return original.get(i);
		}
		// Changed besides its secrets: by its id or name when that is unique.
		for (String key : new String[] { "id", "name" }) {
			if (item.isObject() && item.path(key).isTextual()) {
				String want = item.get(key).asText();
				JsonNode only = null;
				int count = 0;
				for (JsonNode o : original) {
					if (o.isObject() && want.equals(o.path(key).asText(null))) { only = o; count++; }
				}
				return (count == 1) ? only : null;
			}
		}
		return null;   // left as {REDACTED}: reported, and Save refuses it
	}

	/// Equal once every {REDACTED} in `proposed` is taken as matching anything.
	private static boolean sameButSecrets(JsonNode original, JsonNode proposed) {
		if (proposed.isTextual() && REDACTED.equals(proposed.asText())) return original != null && original.isTextual();
		if (original == null || original.getNodeType() != proposed.getNodeType()) return false;
		if (proposed.isObject()) {
			if (original.size() != proposed.size()) return false;
			java.util.Iterator<String> names = proposed.fieldNames();
			while (names.hasNext()) {
				String k = names.next();
				if (!sameButSecrets(original.get(k), proposed.get(k))) return false;
			}
			return true;
		}
		if (proposed.isArray()) {
			if (original.size() != proposed.size()) return false;
			for (int j = 0; j < proposed.size(); j++) if (!sameButSecrets(original.get(j), proposed.get(j))) return false;
			return true;
		}
		return original.equals(proposed);
	}

	/// Paths in `node` still holding `{REDACTED}`: secrets the proposal moved or
	/// added that no original value matches. Saved as is, the marker would
	/// become the credential.
	static List<String> unrestoredSecrets(JsonNode node, String path) {
		List<String> out = new java.util.ArrayList<>();
		if (node == null) return out;
		// Also a marker kept inside other text ("{REDACTED}!", a URL around
		// it): nothing restores that, and saved it is the credential.
		if (node.isTextual() && node.asText().contains(REDACTED)) {
			out.add(path.isEmpty() ? "(root)" : path);
		} else if (node.isObject()) {
			node.fields().forEachRemaining(e -> out.addAll(unrestoredSecrets(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey())));
		} else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) out.addAll(unrestoredSecrets(node.get(i), path + "[" + i + "]"));
		}
		return out;
	}

	/// Marks for encryption every plain value in a password field: a secret
	/// typed in the JSON tab arrives bare (only the form adds `{CLEARTEXT}`),
	/// and the server encrypts only marked values. Already marked or
	/// encrypted values (`{SCHEME}...`) and `${variable}` references stay as
	/// they are.
	static void markPasswordsForEncryption(JsonNode node, JsonNode subschema, JsonNode schemaRoot) {
		subschema = resolveRef(subschema, schemaRoot);
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			java.util.List<String> keys = new java.util.ArrayList<>();
			obj.fieldNames().forEachRemaining(keys::add);
			for (String k : keys) {
				JsonNode child = childSchema(subschema, k, schemaRoot);
				JsonNode v = obj.get(k);
				if (v.isTextual() && bareSecret(v.asText(), child, schemaRoot)) obj.put(k, "{CLEARTEXT}" + v.asText());
				else markPasswordsForEncryption(v, child, schemaRoot);
			}
		} else if (node.isArray()) {
			JsonNode items = (subschema != null) ? subschema.get("items") : null;
			ArrayNode arr = (ArrayNode) node;
			for (int i = 0; i < arr.size(); i++) {
				JsonNode v = arr.get(i);
				if (v.isTextual() && bareSecret(v.asText(), items, schemaRoot)) arr.set(i, "{CLEARTEXT}" + v.asText());
				else markPasswordsForEncryption(v, items, schemaRoot);
			}
		}
	}

	private static boolean bareSecret(String value, JsonNode schema, JsonNode schemaRoot) {
		JsonNode s = resolveRef(schema, schemaRoot);
		return s != null && "password".equals(s.path("format").asText("")) && !value.isEmpty()
				&& !value.matches("^\\{(CLEARTEXT|AES|AES256|3DES)\\}.*") && !value.matches("^\\$\\{[^}]+\\}$")
				&& !REDACTED.equals(value);
	}

	/// Same validation idiom as ValidationAPI: networknt against the app's
	/// generated schema. Returns human-readable messages, empty when valid.
	/// The validator for the schema's own draft (its `$schema`; victools writes
	/// 2020-12), not draft 4: draft 4 has no `const`, so each variant's `type`
	/// discriminator went unchecked.
	static JsonSchemaFactory factoryFor(JsonNode schemaNode) {
		SpecVersion.VersionFlag version;
		try {
			version = com.networknt.schema.SpecVersionDetector.detect(schemaNode);
		} catch (Exception unstated) {
			version = SpecVersion.VersionFlag.V202012;
		}
		return JsonSchemaFactory.getInstance(version);
	}

	static List<String> validate(JsonNode schemaNode, JsonNode configNode) {
		JsonSchemaFactory factory = factoryFor(schemaNode);
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
			throw new Unanswered("The model's response contained no JSON document.");
		}
		return mapper.readTree(trimmed.substring(start, end + 1));
	}
}
