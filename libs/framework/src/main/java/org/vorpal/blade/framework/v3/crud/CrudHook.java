package org.vorpal.blade.framework.v3.crud;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletResponse;
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
	private static final String RECEIVED = "crud.received";

	private static volatile Crud current;
	/// The class of the configuration [#current] came from.
	private static volatile String source;

	/// The configuration's `variables`, beneath every call's own
	/// ([MessageHelper#getSessionVariables]): set from the published section,
	/// or by the CRUD service from its configuration ([#useVariables]).
	private static volatile java.util.Map<String, String> fileVariables = java.util.Collections.emptyMap();

	private CrudHook() {
	}

	/// Keeps `config`'s `crud` section if `config` can carry one. Any other
	/// configuration leaves the current section alone, so an application
	/// with a second, unrelated config file keeps its rules.
	public static void publish(Object config) {
		if (config instanceof CrudCapable) {
			Crud crud = ((CrudCapable) config).getCrud();
			// A second configuration in the same application with no `crud`
			// section leaves the section alone; the configuration that holds it
			// may still take it away.
			if (crud == null && current != null && !config.getClass().getName().equals(source)) return;
			current = crud;
			source = config.getClass().getName();
			useVariables(current != null ? current.getVariables() : null);
		}
	}

	/// Sets the variables every call resolves beneath its own: the CRUD
	/// service's, which has no `crud` section to publish.
	public static void useVariables(java.util.Map<String, String> variables) {
		fileVariables = (variables != null) ? variables : java.util.Collections.emptyMap();
	}

	/// The configuration's variables, read when a `${name}` is resolved, so
	/// a call the application originates has them too and none is stored in
	/// a session.
	public static java.util.Map<String, String> fileVariables() {
		return fileVariables;
	}

	/// Forgets the published section, for tests.
	static void reset() {
		current = null;
		source = null;
		useVariables(null);
	}

	/// The section this application runs, or null.
	public static Crud current() {
		return current;
	}

	/// Starts a call's variables and runs the pipeline on its initial request.
	/// Does nothing without either, which is the usual case.
	public static void select(SipServletRequest request) {
		Crud crud = current;
		if (crud == null || request == null || !crud.enriches()) {
			return;
		}
		try {
			Crud.enrich(crud.getPipeline(), crud.getVariables(), request);
		} catch (Exception e) {
			log(request, e);
		}
	}

	/// Runs the call's rule set on `msg` before it is sent, once per message.
	/// A failing rule is undone and the message goes out as it stands.
	/// Afterwards the sender asks [#refusal] (a request) or [#restatus] (a
	/// response) what the rules decided.
	public static void apply(SipServletMessage msg) {
		run(msg, Crud.SEND_EVENT, APPLIED);
	}

	/// Runs the rules whose `event` names `receive` on a message the
	/// application received, before the application handles it, once per
	/// message. On the initial request this follows [#select], so the
	/// pipeline chose the rule set from the request as it arrived.
	public static void receive(SipServletMessage msg) {
		run(msg, Crud.RECEIVE_EVENT, RECEIVED);
	}

	private static void run(SipServletMessage msg, String event, String marker) {
		Crud crud = current;
		if (crud == null || msg == null || crud.getRuleSets().isEmpty()) {
			return;
		}
		if (msg.getAttribute(marker) != null) {
			return;
		}
		msg.setAttribute(marker, Boolean.TRUE);
		try {
			SipApplicationSession appSession = msg.getApplicationSession();
			// The call's own `ruleSet`, else the file's variable of that name,
			// as `${ruleSet}` resolves.
			String id = (appSession != null)
					? MessageHelper.getVariable(appSession, CrudConfiguration.RULESET_VARIABLE)
					: null;
			if (id == null) id = fileVariables.get(CrudConfiguration.RULESET_VARIABLE);
			RuleSet ruleSet = Crud.ruleSet(crud.getRuleSets(), crud.getDefaultRuleSet(), id, msg);
			if (ruleSet != null) {
				ruleSet.applyRules(msg, event);
			}
		} catch (Exception e) {
			log(msg, e);
		}
	}

	/// The refusal a rule recorded on `request` ([RejectOperation]), or null.
	/// The sender answers the request this one came from with it, and does
	/// not send this one.
	public static RejectOperation.Refusal refusal(SipServletRequest request) {
		Object refusal = (request != null) ? request.getAttribute(MessageHelper.PENDING_REJECT) : null;
		return (refusal instanceof RejectOperation.Refusal) ? (RejectOperation.Refusal) refusal : null;
	}

	/// `refusal` as a response to `upstream`.
	public static SipServletResponse refuse(SipServletRequest upstream, RejectOperation.Refusal refusal) {
		SipServletResponse response = (refusal.reasonPhrase != null && !refusal.reasonPhrase.isEmpty())
				? upstream.createResponse(refusal.status, refusal.reasonPhrase)
				: upstream.createResponse(refusal.status);
		response.setAttribute(APPLIED, Boolean.TRUE);
		return response;
	}

	/// The response to send in place of `response`: a copy with the status
	/// and reason phrase a rule wrote ([MessageHelper#PENDING_STATUS]), or
	/// `response` itself when no rule changed them. The container cannot
	/// change a response's status, so the copy is made from the same request
	/// and takes every header the application may set, the body, and the
	/// message's attributes.
	///
	/// This runs as the response is sent, after the B2BUA has reported the
	/// call's outcome (`callDeclined`, its listener, analytics) with the
	/// callee's status. That order is deliberate: the rules keep the last
	/// word, seeing every edit the listener made. So analytics can record
	/// the callee's 486 while the caller gets the 480 a rule wrote.
	public static SipServletResponse restatus(SipServletResponse response) {
		if (response == null) return null;
		Object status = response.getAttribute(MessageHelper.PENDING_STATUS);
		Object reason = response.getAttribute(MessageHelper.PENDING_REASON);
		if (status == null && reason == null) {
			return response;
		}
		try {
			int code = (status != null) ? (Integer) status : response.getStatus();
			SipServletResponse copy = (reason != null)
					? response.getRequest().createResponse(code, reason.toString())
					: response.getRequest().createResponse(code);
			for (java.util.Iterator<String> names = response.getHeaderNames(); names.hasNext();) {
				String name = names.next();
				// The body headers come with setContent.
				if (name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Content-Length")) {
					continue;
				}
				// Every header the application may write is taken from the
				// original as the rules left it, replacing what the copy got;
				// one only the container writes (Via, Call-ID, CSeq) stays.
				try {
					copy.removeHeader(name);
					for (java.util.ListIterator<String> values = response.getHeaders(name); values.hasNext();) {
						copy.addHeader(name, values.next());
					}
				} catch (IllegalArgumentException | IllegalStateException system) {
					// a header only the container may write
				}
			}
			// A rule's rewrite of To or From, where the container allows it.
			for (String name : new String[] { "To", "From" }) {
				try {
					javax.servlet.sip.Address from = response.getAddressHeader(name);
					javax.servlet.sip.Address to = copy.getAddressHeader(name);
					if (from != null && to != null && !from.getURI().equals(to.getURI())) to.setURI(from.getURI());
					if (from != null && to != null && from.getDisplayName() != null
							&& !from.getDisplayName().equals(to.getDisplayName())) {
						to.setDisplayName(from.getDisplayName());
					}
				} catch (Exception notAllowed) {
					log(response, new IllegalStateException("the " + name + " rewrite is lost: a response's "
							+ name + " cannot be changed on the copy a status change sends"));
				}
			}
			if (response.getRawContent() != null) {
				copy.setContent(response.getRawContent(), response.getContentType());
			}
			for (String name : response.getAttributeNameSet()) {
				copy.setAttribute(name, response.getAttribute(name));
			}
			copy.removeAttribute(MessageHelper.PENDING_STATUS);
			copy.removeAttribute(MessageHelper.PENDING_REASON);
			return copy;
		} catch (Exception e) {
			log(response, e);
			return response;
		}
	}

	private static void log(SipServletMessage msg, Exception e) {
		Logger sipLogger = SettingsManager.getSipLogger();
		if (sipLogger != null) {
			sipLogger.warning(msg, "CrudHook - rules failed: " + e);
		}
	}
}
