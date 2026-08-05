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
/// standard Expression grammar. Lookup falls through to environment
/// variables and system properties, matching the fallback chain rule
/// value templates already use ([Context#substitute]).
class SessionVariablesContext extends Context {

	private final Map<String, String> vars;

	SessionVariablesContext(Map<String, String> vars) {
		super(null);
		this.vars = vars;
	}

	@Override
	public String get(String name) {
		if (name == null) return null;
		String v = (vars != null) ? vars.get(name) : null;
		if (v != null) return v;
		v = System.getenv(name);
		if (v != null) return v;
		return System.getProperty(name);
	}
}
