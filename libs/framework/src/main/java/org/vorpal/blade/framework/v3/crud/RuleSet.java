package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/// A named collection of [Rule]s, run in order. Selectors and translation
/// maps pick which rule set applies to each call; this class just iterates.
///
/// Rules are keyed by id, in the order they run:
///
/// ```json
/// "rules": {
///   "stamp":  { "method": "INVITE", "operations": [ … ] },
///   "reject": { "when": "${toUser} == reject", "operations": [ … ] }
/// }
/// ```
///
/// Keys rather than a list so that a cluster or server file can change one
/// rule: the settings loader merges objects key by key, but appends a list
/// to the list below it, so with a list an overlay could only add rules.
/// With keys, an overlay that names `reject` changes that rule, a new key
/// runs after the inherited ones, and `"enabled": false` switches an
/// inherited rule off. Two rules with one id cannot be written.
///
/// The earlier form, a list of rules each with an `id`, still loads
/// ([RulesReader]); the editors save the keyed form.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "description", "rules" })
public class RuleSet implements Serializable {
	/// 2: rules keyed by id, enum filters, `enabled` (2026-11).
	private static final long serialVersionUID = 2L;

	private String id;
	private String description;
	private Map<String, Rule> rules = new LinkedHashMap<>();

	public RuleSet() {
	}

	public void applyRules(SipServletMessage msg, String lifecycleEvent) {
		for (Rule rule : rules.values()) {
			if (rule.runs() && rule.matches(msg, lifecycleEvent)) {
				SettingsManager.getSipLogger().finer(msg,
						"RuleSet[" + id + "] - applying rule: " + rule.getId());
				// Counted when it applied; one undone changed nothing.
				if (rule.process(msg)) hit(id, rule);
			}
		}
	}

	/// Counter name prefix for rule hits: `crud.rule.<ruleSet>/<rule>`, one
	/// counter per rule in this application's metrics (`Type=Metrics` MBean).
	public static final String HITS = "crud.rule.";

	/// Counts a rule that matched a message on this node. The counter is
	/// looked up once per rule and registry and kept on the rule, so a message
	/// costs one increment; a reload makes new rules, which look it up again.
	static void hit(String ruleSet, Rule rule) {
		org.vorpal.blade.framework.v3.metrics.MetricsRegistry registry = org.vorpal.blade.framework.v3.metrics.MetricsRegistry.ofThisApp();
		if (registry == null) return;
		org.vorpal.blade.framework.v3.metrics.Counter counter = rule.hits;
		if (counter == null || rule.hitsIn != registry) {
			counter = registry.counter(HITS + ruleSet + "/" + rule.getId(), "messages this CRUD rule matched");
			rule.hitsIn = registry;
			rule.hits = counter;
		}
		counter.increment();
	}

	/// Adds each rule under its id, after those already here; a rule whose id
	/// is taken replaces that one in place.
	public RuleSet add(Rule... more) {
		return add(Arrays.asList(more));
	}

	/// Adds each rule under its id ([#add(Rule...)]).
	public RuleSet add(Collection<Rule> more) {
		for (Rule rule : more) rules.put(rule.getId(), rule);
		return this;
	}

	/// The key this rule set has in `ruleSets`, for log lines.
	@JsonIgnore
	public String getId() {
		return id;
	}

	@JsonIgnore
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

	@JsonPropertyDescription("Rules by id, run in the order written. A cluster or server file changes a rule by naming it; a new id runs after the inherited ones.")
	public Map<String, Rule> getRules() {
		return rules;
	}

	@JsonDeserialize(using = RulesReader.class)
	public void setRules(Map<String, Rule> rules) {
		this.rules = (rules != null) ? rules : new LinkedHashMap<>();
		for (Map.Entry<String, Rule> e : this.rules.entrySet()) {
			if (e.getValue() != null) e.getValue().setId(e.getKey());
		}
	}

	/// Reads `rules` keyed by id, or the earlier list form ([KeyedReader]).
	public static class RulesReader extends KeyedReader<Rule> {
		public RulesReader() {
			super("rule", Rule.class);
		}

		@Override
		protected void setId(Rule rule, String id) {
			rule.setId(id);
		}
	}
}
