package org.vorpal.blade.framework.v3;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v3.configuration.Context;

/// Test-only [Context] backed by a plain `Map<String,String>`. Lets
/// smoke tests feed values into the Context without standing up a SIP
/// container. Overrides [Context#resolve(String)] and [Context#get(String)]
/// with a map-backed resolver that still falls through to env vars /
/// system properties so tests can exercise the full fallback chain.
public class FakeContext extends Context {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

	private final Map<String, String> vars = new HashMap<>();

	public FakeContext() {
		super(null);
	}

	public FakeContext set(String name, String value) {
		vars.put(name, value);
		return this;
	}

	public FakeContext clear() {
		vars.clear();
		return this;
	}

	@Override
	public String get(String name) {
		if (name == null) return null;
		String v = vars.get(name);
		if (v != null) return v;
		v = System.getenv(name);
		if (v != null) return v;
		return System.getProperty(name);
	}

	@Override
	public String resolve(String template) {
		if (template == null) return null;
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String expr = m.group(1);
			int colon = expr.indexOf(':');
			String bare = (colon < 0) ? expr : expr.substring(0, colon);
			String value = get(bare);
			m.appendReplacement(out,
					Matcher.quoteReplacement(value != null ? value : m.group(0)));
		}
		m.appendTail(out);
		return out.toString();
	}

	@Override
	public void put(String name, String value) {
		if (name != null && value != null) vars.put(name, value);
	}
}
