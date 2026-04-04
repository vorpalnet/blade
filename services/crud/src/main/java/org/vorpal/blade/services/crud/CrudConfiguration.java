package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.RouterConfig;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Configuration for the CRUD SIP message manipulation service.
 * Extends RouterConfig for Translation-based rule set selection.
 */
@JsonPropertyOrder({ "logging", "session", "selectors", "ruleSets", "defaultRoute", "maps", "plan" })
public class CrudConfiguration extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	private Map<String, RuleSet> ruleSets = new HashMap<>();

	@JsonPropertyDescription("Named rule sets for SIP message manipulation, referenced by Translations")
	public Map<String, RuleSet> getRuleSets() {
		return ruleSets;
	}

	public void setRuleSets(Map<String, RuleSet> ruleSets) {
		this.ruleSets = ruleSets;
	}
}
