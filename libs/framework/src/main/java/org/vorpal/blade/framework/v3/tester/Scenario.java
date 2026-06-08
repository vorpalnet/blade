package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.framework.v3.crud.CrudConfiguration;
import org.vorpal.blade.framework.v3.crud.Rule;
import org.vorpal.blade.framework.v3.crud.RuleSet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One named test behavior. The `role` decides which machinery runs it:
///
/// | role        | machinery                                | typical use                                  |
/// |-------------|------------------------------------------|----------------------------------------------|
/// | `originate` | [LoadEngine] + [OriginateCallflow]       | SIPp-style load; synthesize INVITEs           |
/// | `answer`    | [ScriptedAnswer]                         | mock endpoint: status sequences, transfers    |
/// | `b2bua`     | B2BUA passthrough + rule set             | transform a real call en route (add/strip SIPREC, headers) |
///
/// All roles share `template` (a raw SIP-message fragment merged into the
/// INVITE) and `ruleSet` (CRUD transformations applied across lifecycle
/// events). `responseScript` scripts what to send (answer) or what to expect
/// (originate); `assertions` judge each call from captured variables.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "description", "role", "template", "ruleSet", "rules", "responseScript", "assertions" })
public class Scenario implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final String ROLE_ORIGINATE = "originate";
	public static final String ROLE_ANSWER = "answer";
	public static final String ROLE_B2BUA = "b2bua";

	private String description;
	private String role = ROLE_B2BUA;
	private String template;
	private String ruleSet;
	private List<Rule> rules = new LinkedList<>();
	private ResponseScript responseScript;
	private List<Assertion> assertions = new LinkedList<>();

	public Scenario() {
	}

	/// The effective rule set: the referenced `ruleSet` (shared base, looked
	/// up in `config`) followed by this scenario's inline `rules`. Returns
	/// null when the scenario has no rules at all. A dangling `ruleSet`
	/// reference is silently treated as absent — callers that want to warn
	/// should check `config.getRuleSets()` themselves.
	public RuleSet effectiveRules(CrudConfiguration config) {
		RuleSet referenced = (ruleSet != null && config != null) ? config.getRuleSets().get(ruleSet) : null;
		if (rules == null || rules.isEmpty()) {
			return referenced;
		}
		RuleSet merged = new RuleSet();
		merged.setId((ruleSet != null ? ruleSet : "scenario") + "+inline");
		if (referenced != null) {
			merged.getRules().addAll(referenced.getRules());
		}
		merged.getRules().addAll(rules);
		return merged;
	}

	@JsonPropertyDescription("Human-readable description shown in the configurator.")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("How this scenario handles a call: originate (synthesize), answer (respond locally), or b2bua (transform and forward). Default b2bua.")
	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	@JsonPropertyDescription("Optional template filename in config/custom/vorpal/_templates/ — a raw SIP message fragment (request line, headers, body) merged into the INVITE before rules run.")
	public String getTemplate() {
		return template;
	}

	public void setTemplate(String template) {
		this.template = template;
	}

	@JsonPropertyDescription("Optional rule set id (from ruleSets) applied across the call's lifecycle events.")
	public String getRuleSet() {
		return ruleSet;
	}

	public void setRuleSet(String ruleSet) {
		this.ruleSet = ruleSet;
	}

	@JsonPropertyDescription("Inline rules for this scenario, applied after the referenced ruleSet. Handy when a scenario's transforms aren't shared with anything else.")
	public List<Rule> getRules() {
		return rules;
	}

	public void setRules(List<Rule> rules) {
		this.rules = rules;
	}

	@JsonPropertyDescription("What to send (answer role) or expect (originate role): status sequences, delays, transfer, auto-BYE.")
	public ResponseScript getResponseScript() {
		return responseScript;
	}

	public void setResponseScript(ResponseScript responseScript) {
		this.responseScript = responseScript;
	}

	@JsonPropertyDescription("Per-call pass/fail predicates evaluated against captured session variables when the call's final response arrives.")
	public List<Assertion> getAssertions() {
		return assertions;
	}

	public void setAssertions(List<Assertion> assertions) {
		this.assertions = assertions;
	}
}
