package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A named, ordered collection of [Rule]s. Selectors and translation maps
/// pick which rule set applies to each call; this class just iterates.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "id", "description", "rules" })
public class RuleSet implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description;
	private List<Rule> rules = new LinkedList<>();

	public RuleSet() {
	}

	public void applyRules(SipServletMessage msg, String lifecycleEvent) {
		for (Rule rule : rules) {
			if (rule.matches(msg, lifecycleEvent)) {
				SettingsManager.getSipLogger().finer(msg,
						"RuleSet[" + id + "] - applying rule: " + rule.getId());
				rule.process(msg);
			}
		}
	}

	@JsonPropertyDescription("Unique identifier for this rule set.")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Human-readable description shown in the configurator.")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Rules to evaluate, in order.")
	public List<Rule> getRules() {
		return rules;
	}

	public void setRules(List<Rule> rules) {
		this.rules = rules;
	}
}
