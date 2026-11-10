package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.Pipelines;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// CRUD service configuration, on the v3 enrichment model.
///
/// The [#getPipeline] is an ordered list of v3
/// [Connector]s — the same pipeline shape as
/// [org.vorpal.blade.framework.v3.configuration.RouterConfiguration] —
/// whose job here is to write the **`ruleSet`** context variable naming an
/// entry in [#getRuleSets]. The canonical pipeline is a
/// [org.vorpal.blade.framework.v3.configuration.connectors.SipConnector]
/// extracting keys from the message followed by a
/// [org.vorpal.blade.framework.v3.configuration.connectors.TableConnector]
/// mapping a key to a rule set (see [CrudConfigurationSample]). A call whose
/// pipeline writes no `ruleSet` — or names an unknown one — passes through
/// the B2BUA untransformed.
///
/// There is no routing phase: the CRUD service is a B2BUA that forwards to
/// the inbound Request-URI. The pipeline exists solely to *select*, which is
/// enrichment, so [Connector]s are all this configuration needs.
///
/// Unlike iRouter, the pipeline runs **inline** on the container thread at
/// initial-request time (see [#enrich]) — selection must complete before the
/// B2BUA's `callStarted` fires. The sync connectors (`sip`, `table`)
/// complete instantly; an I/O connector (`rest`, `jdbc`, `ldap`) works but
/// adds its round-trip to INVITE latency, so prefer message-derived
/// selection.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "logging", "session", "variables", "pipeline", "defaultRuleSet", "ruleSets" })
public class CrudConfiguration extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Name of the context variable the pipeline writes to select a rule
	/// set: `ruleSet`. Shared with the tester apps, whose translation-free
	/// pipeline selection reads the same variable.
	public static final String RULESET_VARIABLE = "ruleSet";

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

	@JsonPropertyDescription("Enrichment connectors by id, run in order; must write the `ruleSet` context variable to select a rule set")
	@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = Pipeline.Writer.class)
	public Map<String, Connector> getPipeline() {
		return pipeline;
	}

	@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = Pipeline.Reader.class)
	public void setPipeline(Map<String, Connector> pipeline) {
		this.pipeline = Pipeline.keyed(pipeline);
	}

	/// Adds each connector under its id, after those already here.
	public CrudConfiguration addConnector(Connector... connectors) {
		for (Connector c : connectors) pipeline.put(c.getId(), c);
		return this;
	}

	/// True when there is a pipeline to run on a call's initial request.
	/// `variables` need no run: they are read when used.
	public boolean enriches() {
		return !pipeline.isEmpty();
	}

	@JsonPropertyDescription("Rule set applied when the pipeline writes no `ruleSet` variable. Null means unmatched calls pass through untransformed.")
	public String getDefaultRuleSet() {
		return defaultRuleSet;
	}

	public void setDefaultRuleSet(String defaultRuleSet) {
		this.defaultRuleSet = defaultRuleSet;
	}

	@JsonPropertyDescription("Named rule sets, keyed by id. The pipeline selects one by writing its id to the `ruleSet` context variable.")
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

	/// Runs the pipeline against `request` via [Pipelines#enrich] (inline,
	/// sequential, failures logged and skipped) and returns the enriched
	/// [Context].
	///
	/// On completion every enrichment value is **promoted to the
	/// application session** as a CRUD variable ([MessageHelper#putVariable]),
	/// because that's where rule operations resolve `${var}` from
	/// ([MessageHelper#getSessionVariables]) — selectors write to the inbound
	/// SipSession, which the outbound dialog's messages never see. Promotion
	/// makes pipeline-extracted values (`${dialedNumber}`)
	/// usable inside `create`/`update` templates and `when` clauses.
	public Context enrich(SipServletRequest request) {
		return Crud.enrich(pipeline, variables, request);
	}

	/// Looks up the rule set the enriched `ctx` selected via the
	/// [#RULESET_VARIABLE] variable, falling back to [#getDefaultRuleSet]
	/// when the pipeline wrote nothing. Returns null — passthrough — when
	/// neither names one; logs a warning when the named rule set doesn't
	/// exist (a config error worth surfacing, not silence).
	public RuleSet selectedRuleSet(Context ctx) {
		String id = (ctx != null) ? ctx.get(RULESET_VARIABLE) : null;
		return Crud.ruleSet(ruleSets, defaultRuleSet, id, (ctx != null) ? ctx.getRequest() : null);
	}
}
