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
@JsonPropertyOrder({ "pipeline", "defaultRuleSet", "ruleSets" })
public class Crud implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The lifecycle event an in-application rule sees: `send`.
	public static final String SEND_EVENT = "send";

	private List<Connector> pipeline = new LinkedList<>();
	private String defaultRuleSet;
	private Map<String, RuleSet> ruleSets = new LinkedHashMap<>();

	@JsonPropertyDescription("Ordered enrichment connectors, run once on the call's initial request; may write the `ruleSet` variable to select a rule set. Usually empty.")
	public List<Connector> getPipeline() {
		return pipeline;
	}

	public void setPipeline(List<Connector> pipeline) {
		this.pipeline = (pipeline != null) ? pipeline : new LinkedList<>();
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
	}

	/// Runs `pipeline` against `request` and copies every value it produced
	/// into the call's CRUD variables, where `${var}` and `when` read them.
	/// Shared by the CRUD service ([CrudConfiguration#enrich]) and the
	/// in-application hook ([CrudHook#select]).
	static Context enrich(List<Connector> pipeline, SipServletRequest request) {
		Context ctx = Pipelines.enrich(pipeline, request);
		// snapshot() values are already resolved, so they are stored as they are.
		SipApplicationSession appSession = (request != null) ? request.getApplicationSession() : null;
		if (appSession != null) {
			for (Map.Entry<String, String> e : ctx.snapshot().entrySet()) {
				MessageHelper.putVariable(appSession, e.getKey(), e.getValue());
			}
		}
		return ctx;
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
