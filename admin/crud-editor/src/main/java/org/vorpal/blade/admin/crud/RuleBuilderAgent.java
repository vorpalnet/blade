package org.vorpal.blade.admin.crud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.ai.AiSettings;
import org.vorpal.blade.framework.v3.ai.NumberMask;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.connectors.SipConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.TableConnector;
import org.vorpal.blade.framework.v3.crud.Crud;
import org.vorpal.blade.framework.v3.crud.CrudConfiguration;
import org.vorpal.blade.framework.v3.crud.PreviewEngine;
import org.vorpal.blade.framework.v3.crud.PreviewEngine.PreviewResult;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The AI rule builder: an operator describes a CRUD rule in plain language,
/// and Claude drafts it, checking its own work with the editor's machinery
/// before the operator sees it:
///
/// - `preview` runs a candidate rule on the sample message ([PreviewEngine])
///   and returns the message after it, the rules that fired, any refusal, and
///   the problems Save would report;
/// - `run_tests` runs the saved tests with the candidate in place;
/// - `propose` hands back the finished rule, refused while it still has a
///   problem or breaks a saved test.
///
/// The model works on the editor's unsaved draft, sent as JSON with the
/// request. Nothing it does is saved: the proposal goes back to the page as
/// a draft edit, and Save is the operator's, with every Save check.
///
/// What leaves the AdminServer: the CRUD reference (the `v3.crud` package
/// documentation), the application's `crud` schema, the draft with every
/// credential value replaced by `{REDACTED}`, the sample message, and the
/// operator's words. Phone numbers in all of it are masked ([NumberMask])
/// unless the settings turn that off; the model works with the stand-ins,
/// and the proposal gets the real numbers back.
final class RuleBuilderAgent {

	static final ObjectMapper MAPPER = new ObjectMapper();
	static final String REDACTED = "{REDACTED}";
	/// The most model turns one draft may take.
	static final int MAX_TURNS = 10;

	/// What the page sends: its draft (PreviewServlet's fields), the rule set
	/// on screen and the sample message from the preview inputs, the
	/// operator's words, and the conversation so far. The page is one
	/// conversation; each message is a new run that sees the draft as it is
	/// now, so a follow-up ("make it 486") reads the rule the last one drafted.
	static final class Request {
		public String app;
		public String file;
		/// The rule set on screen: where a new rule goes unless the model names another.
		public String ruleSet;
		/// Optional: a rule to change.
		public String ruleId;
		public String instruction;
		/// Earlier turns, oldest first: `{role: user|assistant, text}`.
		public List<Turn> history;
		public String message;
		public String lifecycleEvent;
		public String leg;
		public Boolean requestSdp;
		public Map<String, String> variables;
		public JsonNode draft;
	}

	/// One earlier turn of the conversation.
	static final class Turn {
		public String role;
		public String text;
	}

	/// What the page gets back: a proposed rule, or, for a question or a
	/// request no rule can meet, only a `reply`.
	static final class Result {
		public String ruleSet;
		public String ruleId;
		public String reply;
		public JsonNode rule;
		public String summary;
		public List<String> steps = new ArrayList<>();
		public long inputTokens;
		public long outputTokens;
		public long cacheReadTokens;
	}

	private final AiSettings settings;
	private final Request request;
	private final JsonNode schema;
	private final Consumer<String> progress;
	private final NumberMask mask;
	/// The draft as the model sees it: credentials redacted, numbers masked.
	private final ObjectNode draft;
	private final String message;
	private Result proposal;

	RuleBuilderAgent(AiSettings settings, Request request, JsonNode schema, Consumer<String> progress) throws IOException {
		this.settings = settings;
		this.request = request;
		this.schema = schema;
		this.progress = (progress != null) ? progress : s -> {
		};
		this.mask = settings.isMaskNumbers() ? new NumberMask() : null;
		JsonNode d = (request.draft != null && request.draft.isObject()) ? request.draft.deepCopy() : MAPPER.createObjectNode();
		redact(d);
		this.draft = (ObjectNode) MAPPER.readTree(masked(MAPPER.writeValueAsString(d)));
		this.message = masked(request.message);
	}

	// ------------------------------------------------------------------
	// The loop

	/// Drafts the rule, or answers in words. Throws when the model cannot be
	/// reached, refuses, or runs out of turns.
	Result run() throws Exception {
		String unavailable = settings.unavailable();
		if (unavailable != null) throw new IllegalStateException(unavailable);
		if (request.instruction == null || request.instruction.trim().isEmpty()) {
			throw new IllegalArgumentException("say what you want");
		}
		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(settings.plainKey());
		boolean gateway = settings.getBaseUrl() != null && !settings.getBaseUrl().trim().isEmpty();
		if (gateway) builder.baseUrl(settings.getBaseUrl().trim());
		AnthropicClient client = builder.build();
		long in = 0, out = 0, cached = 0;
		try {
			List<MessageParam> messages = new ArrayList<>();
			messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(firstMessage()).build());
			for (int turn = 1; turn <= MAX_TURNS; turn++) {
				MessageCreateParams.Builder params = MessageCreateParams.builder()
						.model(settings.getModel() != null && !settings.getModel().isEmpty() ? settings.getModel() : AiSettings.DEFAULT_MODEL)
						.maxTokens(16000L)
						.system(systemPrompt())
						.thinking(ThinkingConfigAdaptive.builder().build())
						.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
						// The reference, schema and history repeat on every turn.
						.cacheControl(CacheControlEphemeral.builder().build())
						.messages(messages);
				for (Tool t : tools()) params.addTool(t);
				if (!gateway) {
					// A refused turn is retried on a model that can take it.
					params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
					params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
				}
				progress.accept("asking the model (turn " + turn + ")");
				Message response = client.messages().create(params.build());
				in += response.usage().inputTokens();
				out += response.usage().outputTokens();
				cached += response.usage().cacheReadInputTokens().orElse(0L);
				StopReason stop = response.stopReason().orElse(null);
				if (StopReason.REFUSAL.equals(stop)) throw new IllegalStateException("the model declined this request");
				if (StopReason.MAX_TOKENS.equals(stop)) throw new IllegalStateException("the model's answer was cut off; try a narrower description");
				messages.add(response.toParam());

				List<ContentBlockParam> results = new ArrayList<>();
				for (ContentBlock block : response.content()) {
					if (!block.isToolUse()) continue;
					ToolUseBlock use = block.asToolUse();
					JsonNode input = MAPPER.valueToTree(use._input().convert(Object.class));
					String answer;
					boolean error = false;
					try {
						answer = call(use.name(), input);
					} catch (Exception e) {
						answer = e.getMessage();
						error = true;
					}
					results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
							.toolUseId(use.id()).content(answer).isError(error).build()));
				}
				if (proposal != null) {
					proposal.inputTokens = in;
					proposal.outputTokens = out;
					proposal.cacheReadTokens = cached;
					return proposal;
				}
				if (results.isEmpty()) {
					// An answer in words: a question, or a request no rule can meet.
					StringBuilder text = new StringBuilder();
					response.content().forEach(b -> b.text().ifPresent(t -> text.append(t.text())));
					Result r = new Result();
					r.reply = unmasked(text.toString().trim());
					r.inputTokens = in;
					r.outputTokens = out;
					r.cacheReadTokens = cached;
					return r;
				}
				messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
			}
			throw new IllegalStateException("no answer after " + MAX_TURNS + " turns; try a narrower request");
		} finally {
			try {
				client.close();
			} catch (Exception ignore) {
				// nothing to do
			}
		}
	}

	// ------------------------------------------------------------------
	// Tools

	/// Runs tool `name` with `input`; the answer is JSON text for the model.
	String call(String name, JsonNode input) throws Exception {
		switch (name) {
		case "preview":
			return preview(input);
		case "run_tests":
			return runTests(input);
		case "propose":
			return propose(input);
		default:
			throw new IllegalArgumentException("no tool named " + name);
		}
	}

	private String preview(JsonNode input) throws Exception {
		String id = ruleId(input);
		progress.accept("previewing rule \"" + id + "\"");
		String set = ruleSetOf(input);
		CrudConfiguration config = configWith(set, id, input.get("rule"));
		String msg = text(input, "message", message);
		if (msg == null || msg.isEmpty()) throw new IllegalArgumentException("no sample message: pass one in `message`");
		ObjectNode out = MAPPER.createObjectNode();
		PreviewResult r;
		CapturingLogger.begin();
		List<String> warnings;
		try {
			Map<String, String> vars = pipelineVariables(set, id, input, msg, out);
			r = PreviewEngine.preview(config, set, msg, text(input, "event", request.lifecycleEvent),
					vars, text(input, "leg", request.leg), request.requestSdp);
		} finally {
			warnings = CapturingLogger.end();
		}
		if (r.error != null) {
			out.put("error", r.error);
			return MAPPER.writeValueAsString(out);
		}
		out.set("fired", MAPPER.valueToTree(r.rulesFired));
		out.set("undone", MAPPER.valueToTree(r.rulesUndone));
		if (r.rejected != null) out.put("refused", r.rejected);
		out.put("leg", r.leg);
		if (r.sdp != null) out.put("sdp", r.sdp);
		out.put("output", r.output);
		out.set("variables", MAPPER.valueToTree(r.variables));
		out.set("warnings", MAPPER.valueToTree(warnings));
		out.set("problems", MAPPER.valueToTree(Crud.problems(config.getRuleSets(), config.getDefaultRuleSet())));
		return MAPPER.writeValueAsString(out);
	}

	/// The variables a rule sees on `msg`: the pipeline's, as the engines
	/// would set them before the rules run, beneath the operator's preview
	/// variables and then the model's own `variables`. Without this a rule
	/// whose `when` reads a pipeline variable never fires in a preview, and
	/// the model, seeing that, concludes the pipeline does not set it.
	///
	/// Only the connectors that read the call itself run (`sip`, `table`): the
	/// model previews many times, and a REST, JDBC or LDAP lookup per preview
	/// would reach outside systems, or a `rate` count move, on its behalf. The
	/// ones skipped are named in `pipelineSkipped`, so the model can supply
	/// their variables. A response runs no pipeline; it reads what the call's
	/// request left.
	private Map<String, String> pipelineVariables(String set, String id, JsonNode input, String msg, ObjectNode out) throws Exception {
		// The operator's values, masked like the message they meet.
		Map<String, String> given = new java.util.LinkedHashMap<>();
		if (request.variables != null) request.variables.forEach((k, v) -> given.put(k, masked(v)));
		Map<String, String> vars = new java.util.LinkedHashMap<>();
		if (!msg.trim().startsWith("SIP/")) {
			CrudConfiguration pipe = configWith(set, id, input.get("rule"));
			if (pipe.getPipeline() != null && !pipe.getPipeline().isEmpty()) {
				Map<String, Connector> local = new java.util.LinkedHashMap<>();
				ArrayNode skipped = MAPPER.createArrayNode();
				pipe.getPipeline().forEach((cid, c) -> {
					if (c instanceof SipConnector || c instanceof TableConnector) local.put(cid, c);
					else skipped.add(cid);
				});
				pipe.setPipeline(local);
				PreviewEngine.SelectionResult sel;
				try {
					sel = PreviewEngine.selectionPreview(pipe, msg, given);
				} catch (RuntimeException e) {
					sel = new PreviewEngine.SelectionResult();
					sel.error = String.valueOf(e.getMessage());
				}
				if (sel.error == null && sel.variables != null) vars.putAll(sel.variables);
				else out.put("pipelineError", sel.error);
				if (skipped.size() > 0) out.set("pipelineSkipped", skipped);
			}
		}
		vars.putAll(given);
		JsonNode own = input.get("variables");
		if (own != null && own.isObject()) {
			own.fields().forEachRemaining(e -> vars.put(e.getKey(), e.getValue().asText()));
		}
		return vars;
	}

	private String runTests(JsonNode input) throws Exception {
		String id = ruleId(input);
		progress.accept("running the saved tests with \"" + id + "\"");
		List<String> failures = testFailures(ruleSetOf(input), id, input.get("rule"));
		ObjectNode out = MAPPER.createObjectNode();
		out.put("passed", failures.isEmpty());
		out.set("failures", MAPPER.valueToTree(failures));
		return MAPPER.writeValueAsString(out);
	}

	private String propose(JsonNode input) throws Exception {
		String id = ruleId(input);
		String set = ruleSetOf(input);
		progress.accept("checking the proposal \"" + id + "\"");
		CrudConfiguration config = configWith(set, id, input.get("rule"));
		List<String> issues = new ArrayList<>(Crud.problems(config.getRuleSets(), config.getDefaultRuleSet()));
		issues.addAll(testFailures(set, id, input.get("rule")));
		if (!issues.isEmpty()) {
			throw new IllegalArgumentException("not accepted, fix these first: " + String.join("; ", issues));
		}
		Result r = new Result();
		r.ruleSet = set;
		r.ruleId = unmasked(id);
		r.rule = MAPPER.readTree(unmasked(MAPPER.writeValueAsString(input.get("rule"))));
		r.summary = unmasked(text(input, "summary", ""));
		proposal = r;
		return "{\"accepted\":true}";
	}

	/// The saved tests' failures with rule `id` in place, numbers masked.
	/// The tests hold real numbers, so the rule runs unmasked.
	private List<String> testFailures(String set, String id, JsonNode rule) throws Exception {
		JsonNode real = MAPPER.readTree(unmasked(MAPPER.writeValueAsString(placed(set, id, rule))));
		CrudConfiguration config = configOf(real);
		List<String> out = new ArrayList<>();
		for (String f : CrudTests.failures(config, CrudTests.load(request.app != null ? request.app : "crud"),
				request.file != null ? request.file : "domain")) {
			out.add(masked(f));
		}
		return out;
	}

	/// The draft with `rule` as rule `id` of rule set `setId`.
	private ObjectNode placed(String setId, String id, JsonNode rule) {
		if (rule == null || !rule.isObject()) throw new IllegalArgumentException("`rule` must be a JSON object");
		ObjectNode d = draft.deepCopy();
		ObjectNode sets = d.with("draftRuleSets");
		JsonNode existing = sets.get(setId);
		ObjectNode set = (existing != null && existing.isObject()) ? (ObjectNode) existing : sets.putObject(setId);
		JsonNode rules = set.get("rules");
		if (rules == null || !rules.isObject()) {
			ObjectNode keyed = MAPPER.createObjectNode();
			if (rules != null && rules.isArray()) {
				for (JsonNode r : rules) {
					if (r.isObject() && r.hasNonNull("id")) {
						ObjectNode c = ((ObjectNode) r).deepCopy();
						keyed.set(c.remove("id").asText(), c);
					}
				}
			}
			set.set("rules", keyed);
			rules = keyed;
		}
		ObjectNode r = ((ObjectNode) rule).deepCopy();
		r.remove("id");
		((ObjectNode) rules).set(id, r);
		return d;
	}

	private CrudConfiguration configWith(String set, String id, JsonNode rule) throws Exception {
		return configOf(placed(set, id, rule));
	}

	/// The rule set a tool call names, else the one on screen.
	private String ruleSetOf(JsonNode input) {
		String set = text(input, "ruleSet", request.ruleSet);
		if (set == null || set.trim().isEmpty()) throw new IllegalArgumentException("`ruleSet` is required: no rule set is on screen");
		return set.trim();
	}

	private static CrudConfiguration configOf(JsonNode draft) throws Exception {
		CrudConfiguration config = new CrudConfiguration();
		PreviewServlet.DraftFields fields;
		try {
			fields = MAPPER.treeToValue(draft, PreviewServlet.DraftFields.class);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalArgumentException("the rule does not load: " + e.getOriginalMessage());
		}
		fields.applyTo(config);
		return config;
	}

	private String ruleId(JsonNode input) {
		String id = text(input, "ruleId", request.ruleId);
		if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("`ruleId` is required");
		if (id.matches("\\d+")) throw new IllegalArgumentException("a rule id of digits only reorders the file; give it a name");
		return id.trim();
	}

	private static String text(JsonNode input, String field, String fallback) {
		JsonNode n = (input != null) ? input.get(field) : null;
		return (n != null && n.isTextual() && !n.asText().isEmpty()) ? n.asText() : fallback;
	}

	static List<Tool> tools() {
		List<Tool> out = new ArrayList<>();
		Map<String, Object> rule = Map.of("type", "object",
				"description", "The rule, in the JSON form of one entry of a rule set's `rules` map (no `id`): filters and `operations`.");
		Map<String, Object> ruleId = Map.of("type", "string",
				"description", "The rule's id: a short name, letters and dashes, not digits only. To change an existing rule, its id.");
		Map<String, Object> ruleSet = Map.of("type", "string",
				"description", "Optional: the rule set the rule belongs to. Default: the one on the operator's screen.");
		out.add(tool("preview", "Run a candidate rule on a SIP message, as the engines would, and see the message after it, "
				+ "the rules that fired or were undone, any refusal, the variables, and the problems Save would report. "
				+ "Use it to check every candidate before proposing.",
				Map.of("ruleId", ruleId, "rule", rule, "ruleSet", ruleSet,
						"message", Map.of("type", "string", "description", "Optional: a SIP message to run on instead of the operator's sample."),
						"event", Map.of("type", "string", "enum", List.of("send", "receive"), "description", "Optional: `send` (the default) or `receive`."),
						"leg", Map.of("type", "string", "enum", List.of("caller", "callee"), "description", "Optional: the leg the message is on."),
						"variables", Map.of("type", "object", "additionalProperties", Map.of("type", "string"),
								"description", "Optional: variables to set before the rules run, over the pipeline's. "
										+ "The pipeline's `sip` and `table` connectors run on the message; the rest do not "
										+ "(`pipelineSkipped` names them), so set what they would.")),
				List.of("ruleId", "rule")));
		out.add(tool("run_tests", "Run the operator's saved tests with the candidate rule in place. Saved tests guard Save: "
				+ "a proposal that breaks one is refused.",
				Map.of("ruleId", ruleId, "rule", rule, "ruleSet", ruleSet), List.of("ruleId", "rule")));
		out.add(tool("propose", "Hand the finished rule to the operator. Refused, with the reasons, while the rule set has a "
				+ "problem or a saved test fails; fix and propose again.",
				Map.of("ruleId", ruleId, "rule", rule, "ruleSet", ruleSet,
						"summary", Map.of("type", "string", "description", "One or two plain sentences: what the rule does and on which messages.")),
				List.of("ruleId", "rule", "summary")));
		return out;
	}

	private static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
		Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
		properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
		return Tool.builder().name(name).description(description)
				.inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build()).build();
	}

	// ------------------------------------------------------------------
	// Prompts

	private String systemPrompt() throws IOException {
		return "You write CRUD rules for BLADE, a SIP servlet framework on Oracle OCCAS. A CRUD rule changes SIP messages "
				+ "an application sends or receives: it creates, reads, updates or deletes headers, the Request-URI, the status, "
				+ "and parts of SDP, XML and JSON bodies, and it can refuse a call. You are a chat beside the operator's editor. "
				+ "When they ask for a change, you draft one rule, check it with the tools, and propose it; they review and save it "
				+ "themselves. When they ask a question (what a rule does, why a message came out as it did), answer it in a few "
				+ "plain sentences without proposing anything. Each message of theirs is a new run that sees the file as it is "
				+ "now, including anything you proposed earlier that they kept.\n\n"
				+ "How to work:\n"
				+ "- Run every candidate with `preview` on the sample message and read the message after it. Check that it does "
				+ "what was asked and nothing else, and that the problems list is empty.\n"
				+ "- Filter the rule as narrowly as the request implies (method, event, leg, sdp, statusRange, when), so it does "
				+ "not touch messages it was not meant for.\n"
				+ "- Prefer the simplest operations that do the job. Reuse the file's variables where they fit.\n"
				+ "- If a saved test fails, decide whether the rule is wrong or the request really changes that behaviour; say "
				+ "which in the summary, but a proposal is refused while a test fails.\n"
				+ "- If the request cannot be expressed as a CRUD rule, say so in plain text instead of proposing.\n"
				+ "- A new rule goes in the rule set on screen unless the request points elsewhere; name another with `ruleSet`. "
				+ "To change a rule, propose it under its own id and rule set.\n"
				+ "- Write replies and summaries for an engineer: short, plain, no headings.\n"
				+ "- Phone numbers in the material may be stand-ins (555...); treat them as the real numbers, "
				+ "written in full wherever the rule needs one. They come back real only when whole, so never put "
				+ "a number or part of one in a rule id; name a rule for what it does.\n"
				+ "- Values shown as " + REDACTED + " are secrets; never use or invent them.\n\n"
				+ "# CRUD reference\n\n" + reference() + "\n\n"
				+ "# The application's `crud` section schema\n\n" + MAPPER.writeValueAsString(schema);
	}

	private String firstMessage() throws IOException {
		StringBuilder b = new StringBuilder();
		if (request.history != null && !request.history.isEmpty()) {
			b.append("The conversation so far:\n");
			// The last few turns are enough to follow a "make it 486 instead".
			List<Turn> recent = request.history.subList(Math.max(0, request.history.size() - 12), request.history.size());
			for (Turn t : recent) {
				if (t == null || t.text == null || t.text.trim().isEmpty()) continue;
				b.append("assistant".equals(t.role) ? "You: " : "Operator: ").append(masked(t.text.trim())).append("\n");
			}
			b.append("\n");
		}
		b.append("Operator: ").append(masked(request.instruction.trim())).append("\n\n");
		b.append("Application: ").append(request.app != null ? request.app : "the CRUD service")
				.append(". File: ").append(request.file != null ? request.file : "domain").append(".\n");
		b.append("Rule set on screen: ").append(request.ruleSet).append(".\n");
		if (request.ruleId != null && !request.ruleId.isEmpty()) {
			b.append("Change the existing rule \"").append(request.ruleId).append("\" (propose it under the same id).\n");
		}
		JsonNode sets = draft.path("draftRuleSets");
		b.append("\nThe rule sets now:\n").append(sets.isMissingNode() ? "(none)" : MAPPER.writeValueAsString(sets)).append("\n");
		JsonNode pipeline = draft.path("draftPipeline");
		if (!pipeline.isMissingNode() && pipeline.size() > 0) {
			b.append("\nThe pipeline (it runs before the rules and sets variables; one of its values may pick the rule set):\n")
					.append(MAPPER.writeValueAsString(pipeline)).append("\n");
		}
		if (draft.hasNonNull("draftDefaultRuleSet")) {
			b.append("Default rule set: ").append(draft.get("draftDefaultRuleSet").asText()).append(".\n");
		}
		JsonNode vars = draft.path("draftVariables");
		if (vars.size() > 0) b.append("\nThe file's variables: ").append(MAPPER.writeValueAsString(vars)).append("\n");
		if (request.variables != null && !request.variables.isEmpty()) {
			b.append("Preview inputs (variables an earlier step would have set): ")
					.append(masked(MAPPER.writeValueAsString(request.variables))).append("\n");
		}
		b.append("\nSample message (")
				.append(request.lifecycleEvent != null ? request.lifecycleEvent : "send")
				.append(request.leg != null && !request.leg.isEmpty() ? ", " + request.leg + " leg" : "").append("):\n")
				.append(message != null && !message.isEmpty() ? message : "(none: write a short realistic one and pass it in `message`)");
		return b.toString();
	}

	/// The `v3.crud` package documentation, copied into this WAR at build time:
	/// the `///` comment lines of `package-info.java`, as Markdown.
	static String reference() throws IOException {
		try (InputStream in = RuleBuilderAgent.class.getResourceAsStream("reference/package-info.java")) {
			if (in == null) return "(the reference is missing from this build)";
			StringBuilder b = new StringBuilder();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String t = line.trim();
				if (t.startsWith("///")) b.append(t.length() > 3 && t.charAt(3) == ' ' ? t.substring(4) : t.substring(3)).append('\n');
			}
			return b.toString();
		}
	}

	// ------------------------------------------------------------------
	// Masking and redaction

	private String masked(String s) {
		return (mask != null) ? mask.mask(s) : s;
	}

	private String unmasked(String s) {
		return (mask != null) ? mask.unmask(s) : s;
	}

	/// A credential value: `{CLEARTEXT}` awaiting encryption or an encrypted
	/// form (`{AES}`, `{AES256}`…), matched by shape, as the Configurator's
	/// assistant does, so a new scheme cannot slip through.
	static boolean credential(String value) {
		return value != null && value.matches("^\\{[A-Za-z0-9]+\\}.*");
	}

	/// Replaces, in place, every credential value (`{AES}`, `{CLEARTEXT}`…).
	static void redact(JsonNode node) {
		if (node instanceof ObjectNode) {
			ObjectNode o = (ObjectNode) node;
			List<String> names = new ArrayList<>();
			o.fieldNames().forEachRemaining(names::add);
			for (String k : names) {
				JsonNode v = o.get(k);
				if (v.isTextual() && credential(v.asText())) o.put(k, REDACTED);
				else redact(v);
			}
		} else if (node instanceof ArrayNode) {
			ArrayNode a = (ArrayNode) node;
			for (int i = 0; i < a.size(); i++) {
				JsonNode v = a.get(i);
				if (v.isTextual() && credential(v.asText())) a.set(i, MAPPER.getNodeFactory().textNode(REDACTED));
				else redact(v);
			}
		}
	}
}
