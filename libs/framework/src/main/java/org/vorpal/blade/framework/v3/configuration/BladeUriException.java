package org.vorpal.blade.framework.v3.configuration;

/// Thrown when a [Resolvable] cannot turn its (substituted, tidied) template
/// into a typed value. Carries the original template and the fully-resolved
/// string so the failure names the offending config value instead of surfacing
/// as an opaque parse error or NPE three frames deep.
public class BladeUriException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String template;
	private final String resolved;

	public BladeUriException(String template, String resolved, Throwable cause) {
		super("Could not build a URI from template [" + template + "] (resolved to [" + resolved + "])", cause);
		this.template = template;
		this.resolved = resolved;
	}

	/// The original `${var}` template, as configured.
	public String getTemplate() {
		return template;
	}

	/// The string after substitution and tidying, i.e. what failed to parse.
	public String getResolved() {
		return resolved;
	}
}
