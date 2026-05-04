package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.RouterConfig;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// CRUD service configuration. Extends [RouterConfig] so the standard
/// selectors / translation maps / routing plan pick which [RuleSet] applies
/// to each call (the matched translation must carry a `ruleSet` attribute).
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "logging", "session", "selectors", "ruleSets", "defaultRoute", "maps", "plan" })
public class CrudConfiguration extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	private Map<String, RuleSet> ruleSets = new LinkedHashMap<>();

	@JsonPropertyDescription("Named rule sets, keyed by id. Translations select one via the `ruleSet` attribute.")
	public Map<String, RuleSet> getRuleSets() {
		return ruleSets;
	}

	public void setRuleSets(Map<String, RuleSet> ruleSets) {
		this.ruleSets = ruleSets;
	}
}
