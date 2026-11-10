package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.Pipelines;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/// The `crud` section of a v3 application's configuration: the CRUD service's
/// rules, run inside the application itself, so a header change no longer
/// needs the CRUD service chained in front of the application in FSMAR.
///
/// The fields mean what they mean in [CrudConfiguration], and a rule set is
/// written the same way. What differs is when the rules run. [CrudHook] applies
/// them to every message a v3 callflow sends, just before it goes out, so
/// CRUD's edits land after the application's own and win any conflict. The
/// rules see the lifecycle event [#SEND_EVENT], never the B2BUA names
/// (`callStarted`, …): leave `event` unset and filter on `method`,
/// `messageType`, `statusRange` and `when`.
///
/// The [#getPipeline] runs once, on the call's initial request, and selects a
/// rule set by writing the `ruleSet` variable. Most applications need no
/// pipeline: [#getDefaultRuleSet] alone applies one rule set to every call, and
/// a rule's `when` clause decides per message. That is how a transfer rewrites
/// `To` only for some targets: the target does not exist when the call's first
/// INVITE arrives, so the decision has to wait for the INVITE that carries it.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "variables", "pipeline", "defaultRuleSet", "ruleSets" })
public class Crud implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The lifecycle event an in-application rule sees: `send`.
	public static final String SEND_EVENT = "send";

	/// The event a rule sees on a message the application received, before the
	/// application handles it. Only a rule whose `event` names it runs then.
	public static final String RECEIVE_EVENT = "receive";

	private Map<String, String> variables = new LinkedHashMap<>();
	private Map<String, Connector> pipeline = new LinkedHashMap<>();
	private String defaultRuleSet;
	private Map<String, RuleSet> ruleSets = new LinkedHashMap<>();

	@JsonPropertyDescription("Named values every call starts with, read as `${name}` by the pipeline, `when` and operations. A cluster or server file sets its own by naming them.")
	public Map<String, String> getVariables() {
		return variables;
	}

	/// A `null` value removes the variable: what a cluster or server file
	/// writes to unset one it inherits.
	public void setVariables(Map<String, String> variables) {
		this.variables = new LinkedHashMap<>();
		if (variables != null) variables.forEach((k, v) -> { if (v != null) this.variables.put(k, v); });
	}

	@JsonPropertyDescription("Enrichment connectors by id, run in order once on the call's initial request; may write the `ruleSet` variable to select a rule set. Usually empty.")
	@JsonSerialize(using = Pipeline.Writer.class)
	public Map<String, Connector> getPipeline() {
		return pipeline;
	}

	@JsonDeserialize(using = Pipeline.Reader.class)
	public void setPipeline(Map<String, Connector> pipeline) {
		this.pipeline = Pipeline.keyed(pipeline);
	}

	/// True when there is a pipeline to run on a call's initial request.
	/// `variables` need no run: they are read when used.
	public boolean enriches() {
		return !pipeline.isEmpty();
	}

	/// Adds each connector under its id, after those already here.
	public Crud addConnector(Connector... connectors) {
		for (Connector c : connectors) pipeline.put(c.getId(), c);
		return this;
	}

	@JsonPropertyDescription("Rule set applied when the pipeline selects none. With no pipeline, the rule set every call gets.")
	public String getDefaultRuleSet() {
		return defaultRuleSet;
	}

	public void setDefaultRuleSet(String defaultRuleSet) {
		this.defaultRuleSet = defaultRuleSet;
	}

	@JsonPropertyDescription("Named rule sets, keyed by id. Rules run on each message the application sends; filter them by method and `when`, not by event.")
	public Map<String, RuleSet> getRuleSets() {
		return ruleSets;
	}

	public void setRuleSets(Map<String, RuleSet> ruleSets) {
		this.ruleSets = (ruleSets != null) ? ruleSets : new LinkedHashMap<>();
		// A rule set's id is its key, as a rule's is ([RuleSet#setRules]).
		for (Map.Entry<String, RuleSet> e : this.ruleSets.entrySet()) {
			if (e.getValue() != null) e.getValue().setId(e.getKey());
		}
	}

	/// Runs `pipeline` against `request` with `variables` beneath the call's
	/// own, and copies every value the pipeline produced into the call's CRUD
	/// variables, where `${var}` and `when` read them. The file's variables
	/// themselves are not copied: they are read from the configuration when
	/// used ([CrudHook#fileVariables]). Shared by the CRUD service
	/// ([CrudConfiguration#enrich]) and the in-application hook
	/// ([CrudHook#select]).
	static Context enrich(Map<String, Connector> pipeline, Map<String, String> variables, SipServletRequest request) {
		// A context for rules: strict templates, and connector output kept in
		// the context, never written onto the hosting application's sessions.
		Context ctx = Pipelines.run(pipeline.values(), Context.forRules(request, variables));
		SipApplicationSession appSession = (request != null) ? request.getApplicationSession() : null;
		if (appSession != null) {
			// Exactly what the connectors wrote; the file's variables are read
			// from the configuration when used ([CrudHook#fileVariables]).
			for (Map.Entry<String, String> e : ctx.written().entrySet()) {
				MessageHelper.putVariable(appSession, e.getKey(), e.getValue());
			}
		}
		return ctx;
	}



	/// Every problem [Rule#problems] finds in `ruleSets`, each prefixed with
	/// where it is (`rule set minsdp, rule strip: operation 2: …`), plus a
	/// `defaultRuleSet` that names no rule set. Empty when there is none. The
	/// editors call it on Save, for the CRUD service and an application's
	/// own `crud` section alike.
	public static List<String> problems(Map<String, RuleSet> ruleSets, String defaultRuleSet) {
		List<String> out = new java.util.ArrayList<>();
		if (defaultRuleSet != null && !defaultRuleSet.isEmpty()
				&& (ruleSets == null || ruleSets.get(defaultRuleSet) == null)) {
			out.add("defaultRuleSet: no rule set is named \"" + defaultRuleSet + "\"");
		}
		if (ruleSets == null) return out;
		for (Map.Entry<String, RuleSet> set : ruleSets.entrySet()) {
			if (set.getValue() == null) continue;
			for (String id : set.getValue().getRules().keySet()) {
				if (id.matches("\\d+")) {
					// JavaScript, and so the editors, put such keys first.
					out.add("rule set " + set.getKey() + ", rule " + id
							+ ": an id of digits only changes the rules' order in an editor; give it a name");
				}
			}
			for (Map.Entry<String, Rule> rule : set.getValue().getRules().entrySet()) {
				if (rule.getValue() == null) continue;
				String where = "rule set " + set.getKey() + ", rule " + rule.getKey() + ": ";
				for (String problem : rule.getValue().problems()) out.add(where + problem);
			}
		}
		return out;
	}

	/// The rule set named `id`, or `defaultId` when `id` is null. Null means
	/// the message passes untouched. Naming a rule set that does not exist is a
	/// configuration error, so it is logged against `msg`.
	static RuleSet ruleSet(Map<String, RuleSet> ruleSets, String defaultId, String id, SipServletMessage msg) {
		if (id == null) {
			id = defaultId;
		}
		if (id == null) {
			return null;
		}
		RuleSet ruleSet = ruleSets.get(id);
		if (ruleSet == null) {
			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null) {
				sipLogger.warning(msg, "CRUD selected unknown ruleSet '" + id + "'");
			}
		}
		return ruleSet;
	}
}
