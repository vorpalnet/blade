package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.crud.CrudConfiguration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Configuration for BLADE test apps (test-uac, test-uas). Extends
/// [CrudConfiguration], so transformation `ruleSets` and the selector /
/// translation-map / plan machinery come along for free; this adds the
/// scenario layer on top.
///
/// A call picks its [Scenario] in priority order:
///
/// 1. a `scenario=` Request-URI parameter naming an entry in `scenarios`
/// 2. a matched translation carrying a `scenario` attribute (or a bare
///    `ruleSet` attribute, which behaves as an unnamed b2bua scenario —
///    full CRUD-service compatibility)
/// 3. the `status` / `delay` / `refer` Request-URI shorthands, which
///    synthesize an ephemeral `answer` scenario
/// 4. `defaultScenario`
///
/// Originated (load-generated) calls use `originate.scenario` unless the
/// REST/JMX start request names one explicitly.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "logging", "session", "defaultScenario", "originate", "scenarios", "ruleSets", "selectors",
		"defaultRoute", "maps", "plan" })
public class TesterConfiguration extends CrudConfiguration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String defaultScenario;
	private OriginateSettings originate = new OriginateSettings();
	private Map<String, Scenario> scenarios = new LinkedHashMap<>();

	@JsonPropertyDescription("Scenario applied when no URI parameter, translation, or shorthand selects one. Null falls back to the app's built-in default behavior.")
	public String getDefaultScenario() {
		return defaultScenario;
	}

	public void setDefaultScenario(String defaultScenario) {
		this.defaultScenario = defaultScenario;
	}

	@JsonPropertyDescription("Defaults for originated (load-generated) calls: address patterns, call duration, pacing.")
	public OriginateSettings getOriginate() {
		return originate;
	}

	public void setOriginate(OriginateSettings originate) {
		this.originate = originate;
	}

	@JsonPropertyDescription("Named scenarios, keyed by id. Each combines a role (originate/answer/b2bua), an optional template and rule set, a response script, and assertions.")
	public Map<String, Scenario> getScenarios() {
		return scenarios;
	}

	public void setScenarios(Map<String, Scenario> scenarios) {
		this.scenarios = scenarios;
	}
}
