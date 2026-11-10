package org.vorpal.blade.framework.v3.crud;

import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

/// A [Context] backed by a plain variable map instead of a live
/// [javax.servlet.sip.SipServletRequest].
///
/// [Expression] evaluates against a Context, but CRUD rules run on
/// messages — requests AND responses — whose variables live on the
/// application session ([MessageHelper#getSessionVariables]). This adapter
/// lets a [Rule]'s `when` clause evaluate those variables through the
/// standard Expression grammar. It resolves as CRUD templates do
/// ([Context#forRules]): a plain name is the call's variable or nothing, never
/// an environment variable or system property, and `${env:}`/`${sys:}` read
/// only names meant for rules, so a `when` cannot test a server secret.
class SessionVariablesContext extends Context {
	private final Map<String, String> vars;

	SessionVariablesContext(Map<String, String> vars) {
		super(null, null, true);
		this.vars = vars;
	}

	@Override
	public String get(String name) {
		if (name == null) return null;
		return (vars != null) ? vars.get(name) : null;
	}
}
