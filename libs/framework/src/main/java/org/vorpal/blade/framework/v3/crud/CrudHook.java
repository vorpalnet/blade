package org.vorpal.blade.framework.v3.crud;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;

/// Runs an application's own [Crud] section. Three calls make up the whole
/// feature:
///
/// - [#publish], from the v3 `SettingsManager` on every config load, keeps the
///   section of whichever config is [CrudCapable].
/// - [#select], from the v3 `AsyncSipServlet` before `chooseCallflow` on an
///   initial request, runs the section's pipeline.
/// - [#apply], from the top of the v3 `Callflow.sendRequest` and
///   `sendResponse`, runs the selected rule set on the outgoing message.
///
/// The section is a static of this class. The framework JAR sits in each WAR's
/// `WEB-INF/lib`, so each application has its own copy, the way the v2
/// `SettingsManager` keeps `analytics` and `eventBus`. The call keeps only the
/// selected rule set's name, in the `ruleSet` variable, and looks it up in the
/// current section on every message. A replicated call therefore carries a
/// string instead of the rule set, and a config reload reaches calls already
/// in progress.
///
/// What [#apply] does not reach: messages the container generates itself
/// (`100 Trying`, its automatic responses), and most of a passthru call. A
/// passthru hand-off proxies the caller's own INVITE, taking each outbound
/// request's Request-URI and attributes; the v3 `Callflow` runs the rules on
/// that INVITE too, so a header rule takes effect, but the container refuses a
/// change to the From or To of a request it received, and that rule is logged
/// and skipped. The callee's responses and everything after the hand-off do
/// not pass through a callflow at all.
public final class CrudHook {

	/// Message attribute marking a message the rules already ran on. A parked
	/// request is sent again when its turn comes, and `create` must not add a
	/// second copy of its header.
	private static final String APPLIED = "crud.applied";

	private static volatile Crud current;

	private CrudHook() {
	}

	/// Keeps `config`'s `crud` section if `config` can carry one. Any other
	/// configuration leaves the current section alone, so an application
	/// with a second, unrelated config file keeps its rules.
	public static void publish(Object config) {
		if (config instanceof CrudCapable) {
			current = ((CrudCapable) config).getCrud();
		}
	}

	/// The section this application runs, or null.
	public static Crud current() {
		return current;
	}

	/// Runs the pipeline on a call's initial request. Does nothing without a
	/// pipeline, which is the usual case.
	public static void select(SipServletRequest request) {
		Crud crud = current;
		if (crud == null || request == null || crud.getPipeline().isEmpty()) {
			return;
		}
		try {
			Crud.enrich(crud.getPipeline(), request);
		} catch (Exception e) {
			log(request, e);
		}
	}

	/// Runs the call's rule set on `msg` before it is sent, once per message.
	/// A failing rule is logged and the message goes out as it stands.
	public static void apply(SipServletMessage msg) {
		Crud crud = current;
		if (crud == null || msg == null || crud.getRuleSets().isEmpty()) {
			return;
		}
		if (msg.getAttribute(APPLIED) != null) {
			return;
		}
		msg.setAttribute(APPLIED, Boolean.TRUE);
		try {
			SipApplicationSession appSession = msg.getApplicationSession();
			String id = (appSession != null)
					? MessageHelper.getVariable(appSession, CrudConfiguration.RULESET_VARIABLE)
					: null;
			RuleSet ruleSet = Crud.ruleSet(crud.getRuleSets(), crud.getDefaultRuleSet(), id, msg);
			if (ruleSet != null) {
				ruleSet.applyRules(msg, Crud.SEND_EVENT);
			}
		} catch (Exception e) {
			log(msg, e);
		}
	}

	private static void log(SipServletMessage msg, Exception e) {
		Logger sipLogger = SettingsManager.getSipLogger();
		if (sipLogger != null) {
			sipLogger.warning(msg, "CrudHook - rules failed: " + e);
		}
	}
}
