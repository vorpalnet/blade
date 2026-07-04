package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.Map;

/// A config value that holds a `${variable}` **template** and resolves it to a
/// typed value `T` on demand — the v3 answer to "90% of runtime errors are bad
/// URIs."
///
/// Unlike typing a config field directly as `SipURI`/`URI` (an interface that
/// can't hold a template and that the Configurator renders as an object
/// sub-form), a `Resolvable` is a concrete value type: it persists as a plain
/// JSON **string** (its template), survives load even when full of unresolved
/// `${var}` (templates only resolve at runtime against live data), and fails
/// loud and located via [BladeUriException] when a resolved value won't parse.
///
/// Resolution is one path — substitute → (subclass) tidy+parse — so there is a
/// single place a URI can go wrong:
/// ```java
/// public FooConfig extends ... { public ResolvableSipUri destination; }
/// // ...at runtime, with a live request:
/// SipURI dest = cfg.destination.resolve(MessageHelper.getSessionVariables(appSession));
/// ```
///
/// @param <T> the typed value this template resolves to (e.g. `SipURI`)
public abstract class Resolvable<T> implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The only persisted state: the `${var}` template string.
	protected String template;

	public Resolvable() {
	}

	public Resolvable(String template) {
		this.template = template;
	}

	public String getTemplate() {
		return template;
	}

	public void setTemplate(String template) {
		this.template = template;
	}

	/// Substitute `${var}` against `vars`, then turn the result into `T`.
	/// Throws [BladeUriException] if the resolved string won't parse.
	public T resolve(Map<String, String> vars) {
		return parse(Context.substitute(template, vars));
	}

	/// Convenience overload that snapshots variables from a [Context].
	public T resolve(Context context) {
		return resolve(context.snapshot());
	}

	/// Turn the fully-substituted string into the typed value. Subclasses apply
	/// any type-specific tidying here and wrap parse failures in
	/// [BladeUriException].
	protected abstract T parse(String resolved);

	@Override
	public String toString() {
		return template;
	}

	@Override
	public int hashCode() {
		return (template == null) ? 0 : template.hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || getClass() != other.getClass()) {
			return false;
		}
		String that = ((Resolvable<?>) other).template;
		return (template == null) ? (that == null) : template.equals(that);
	}
}
