package org.vorpal.blade.framework.v2.testing;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.sip.Address;
import javax.servlet.sip.URI;

/**
 * A mock implementation of Address for unit testing, with real parsing and
 * rendering so From, To and Contact headers behave without a SIP container.
 *
 * <p>Understands {@code "Display Name" <sip:user@host>;tag=abc} as well as a
 * bare {@code sip:user@host}, and writes it back the same way. Header
 * parameters (the ones after the angle brackets, such as {@code tag}) are kept
 * separate from the URI's own parameters, which is the distinction that matters
 * when copying headers between messages.
 *
 * <p>{@code getQ()} and {@code getExpires()} read the {@code q} and
 * {@code expires} parameters, returning -1 when absent.
 */
public class DummyAddress implements Address {
	private static final long serialVersionUID = 1L;

	private String displayName;
	private URI uri;
	private final Map<String, String> parameters = new LinkedHashMap<>();

	/**
	 * Parses an address.
	 *
	 * @param address the address text, for example {@code "Alice" <sip:alice@example.com>;tag=1}
	 */
	public DummyAddress(String address) {
		if (address == null) {
			return;
		}
		String rest = address.trim();

		if (rest.startsWith("\"")) {
			int close = rest.indexOf('"', 1);
			if (close > 0) {
				this.displayName = rest.substring(1, close);
				rest = rest.substring(close + 1).trim();
			}
		}

		int open = rest.indexOf('<');
		if (open >= 0) {
			int close = rest.indexOf('>', open);
			if (close > 0) {
				if (this.displayName == null && open > 0) {
					String bare = rest.substring(0, open).trim();
					if (!bare.isEmpty()) {
						this.displayName = bare;
					}
				}
				this.uri = new DummySipURI(rest.substring(open + 1, close));
				parseParameters(rest.substring(close + 1));
				return;
			}
		}

		// No angle brackets: everything up to the first ';' is the URI. Note that a
		// bare address cannot carry header parameters per RFC 3261, but accepting
		// them here keeps the mock forgiving.
		int semi = rest.indexOf(';');
		if (semi >= 0) {
			this.uri = new DummySipURI(rest.substring(0, semi));
			parseParameters(rest.substring(semi));
		} else {
			this.uri = new DummySipURI(rest);
		}
	}

	/**
	 * Wraps an existing URI.
	 *
	 * @param uri the address's URI
	 */
	public DummyAddress(URI uri) {
		this.uri = uri;
	}

	private void parseParameters(String tail) {
		for (String param : tail.split(";")) {
			String p = param.trim();
			if (p.isEmpty()) {
				continue;
			}
			int eq = p.indexOf('=');
			if (eq >= 0) {
				parameters.put(p.substring(0, eq), p.substring(eq + 1));
			} else {
				parameters.put(p, "");
			}
		}
	}

	/** {@inheritDoc} */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		if (displayName != null) {
			sb.append('"').append(displayName).append("\" ");
		}
		sb.append('<').append(uri == null ? "" : uri.toString()).append('>');
		for (Map.Entry<String, String> p : parameters.entrySet()) {
			sb.append(';').append(p.getKey());
			if (p.getValue() != null && !p.getValue().isEmpty()) {
				sb.append('=').append(p.getValue());
			}
		}
		return sb.toString();
	}

	/** {@inheritDoc} */
	@Override
	public String getDisplayName() {
		return displayName;
	}

	/** {@inheritDoc} */
	@Override
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	/** {@inheritDoc} */
	@Override
	public URI getURI() {
		return uri;
	}

	/** {@inheritDoc} */
	@Override
	public void setURI(URI uri) {
		this.uri = uri;
	}

	/** {@inheritDoc} */
	@Override
	public boolean isWildcard() {
		return uri != null && "*".equals(uri.toString());
	}

	/** Returns the q parameter, or -1 when absent. */
	@Override
	public float getQ() {
		String q = parameters.get("q");
		try {
			return q == null ? -1f : Float.parseFloat(q);
		} catch (NumberFormatException notANumber) {
			return -1f;
		}
	}

	/** {@inheritDoc} */
	@Override
	public void setQ(float q) {
		parameters.put("q", String.valueOf(q));
	}

	/** Returns the expires parameter, or -1 when absent. */
	@Override
	public int getExpires() {
		String expires = parameters.get("expires");
		try {
			return expires == null ? -1 : Integer.parseInt(expires);
		} catch (NumberFormatException notANumber) {
			return -1;
		}
	}

	/** {@inheritDoc} */
	@Override
	public void setExpires(int expires) {
		parameters.put("expires", String.valueOf(expires));
	}

	/** The URI, which is an address's Parameterable value. */
	@Override
	public String getValue() {
		return uri == null ? null : uri.toString();
	}

	/** {@inheritDoc} */
	@Override
	public void setValue(String value) {
		this.uri = new DummySipURI(value);
	}

	/** Header parameters only; the URI keeps its own. */
	@Override
	public String getParameter(String name) {
		return parameters.get(name);
	}

	/** {@inheritDoc} */
	@Override
	public void setParameter(String name, String value) {
		parameters.put(name, value);
	}

	/** {@inheritDoc} */
	@Override
	public void removeParameter(String name) {
		parameters.remove(name);
	}

	/** {@inheritDoc} */
	@Override
	public Iterator<String> getParameterNames() {
		return getParameterNameSet().iterator();
	}

	/** {@inheritDoc} */
	@Override
	public Set<String> getParameterNameSet() {
		return new LinkedHashSet<>(parameters.keySet());
	}

	/** {@inheritDoc} */
	@Override
	public Set<Map.Entry<String, String>> getParameters() {
		return new LinkedHashMap<>(parameters).entrySet();
	}

	/** {@inheritDoc} */
	@Override
	public DummyAddress clone() {
		return new DummyAddress(this.toString());
	}

	/** Two addresses are equal when they render identically. */
	@Override
	public boolean equals(Object other) {
		return other instanceof DummyAddress && other.toString().equals(this.toString());
	}

	/** {@inheritDoc} */
	@Override
	public int hashCode() {
		return toString().hashCode();
	}
}
