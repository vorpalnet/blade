package org.vorpal.blade.framework.v2.testing;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.sip.SipURI;

/**
 * A mock implementation of SipURI for unit testing, with real parsing and
 * rendering so URI manipulation can be exercised without a SIP container.
 *
 * <p>Understands {@code scheme:user:password@host:port;params?headers} and
 * writes it back out the same way. Parameters keep insertion order, and a flag
 * parameter with no value (such as {@code lr}) round-trips as one. The named
 * accessors — transport, maddr, method, ttl, user, lr — read and write the
 * matching parameter, so they never disagree with {@link #getParameter(String)}.
 *
 * <p>{@code isSecure()} reports whether the scheme is {@code sips}.
 */
public class DummySipURI implements SipURI {
	private static final long serialVersionUID = 1L;

	private String scheme = "sip";
	private String user;
	private String userPassword;
	private String host = "";
	private int port = -1;
	private final Map<String, String> parameters = new LinkedHashMap<>();
	private final Map<String, String> uriHeaders = new LinkedHashMap<>();

	/**
	 * Parses a SIP URI.
	 *
	 * @param uri the URI text, for example {@code sip:alice@example.com;transport=tcp}
	 */
	public DummySipURI(String uri) {
		if (uri == null) {
			return;
		}
		String rest = uri.trim();
		if (rest.startsWith("<") && rest.endsWith(">")) {
			rest = rest.substring(1, rest.length() - 1).trim();
		}

		int colon = rest.indexOf(':');
		if (colon > 0 && !rest.substring(0, colon).contains("@")) {
			this.scheme = rest.substring(0, colon);
			rest = rest.substring(colon + 1);
		}

		int question = rest.indexOf('?');
		if (question >= 0) {
			for (String pair : rest.substring(question + 1).split("&")) {
				putPair(uriHeaders, pair);
			}
			rest = rest.substring(0, question);
		}

		int semi = rest.indexOf(';');
		if (semi >= 0) {
			for (String param : rest.substring(semi + 1).split(";")) {
				putPair(parameters, param);
			}
			rest = rest.substring(0, semi);
		}

		int at = rest.lastIndexOf('@');
		if (at >= 0) {
			String userInfo = rest.substring(0, at);
			rest = rest.substring(at + 1);
			int pwd = userInfo.indexOf(':');
			if (pwd >= 0) {
				this.user = userInfo.substring(0, pwd);
				this.userPassword = userInfo.substring(pwd + 1);
			} else {
				this.user = userInfo;
			}
		}

		int portColon = rest.lastIndexOf(':');
		if (portColon >= 0 && rest.indexOf(']') < portColon) { // not inside an IPv6 literal
			try {
				this.port = Integer.parseInt(rest.substring(portColon + 1));
				rest = rest.substring(0, portColon);
			} catch (NumberFormatException notAPort) {
				// leave the colon in the host
			}
		}
		this.host = rest;
	}

	private static void putPair(Map<String, String> into, String pair) {
		if (pair.isEmpty()) {
			return;
		}
		int eq = pair.indexOf('=');
		if (eq >= 0) {
			into.put(pair.substring(0, eq), pair.substring(eq + 1));
		} else {
			into.put(pair, ""); // flag parameter, e.g. lr
		}
	}

	/** {@inheritDoc} */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(scheme).append(':');
		if (user != null) {
			sb.append(user);
			if (userPassword != null) {
				sb.append(':').append(userPassword);
			}
			sb.append('@');
		}
		sb.append(host);
		if (port >= 0) {
			sb.append(':').append(port);
		}
		for (Map.Entry<String, String> p : parameters.entrySet()) {
			sb.append(';').append(p.getKey());
			if (p.getValue() != null && !p.getValue().isEmpty()) {
				sb.append('=').append(p.getValue());
			}
		}
		boolean first = true;
		for (Map.Entry<String, String> h : uriHeaders.entrySet()) {
			sb.append(first ? '?' : '&').append(h.getKey()).append('=').append(h.getValue());
			first = false;
		}
		return sb.toString();
	}

	/** {@inheritDoc} */
	@Override
	public String getScheme() {
		return scheme;
	}

	/** {@inheritDoc} */
	@Override
	public boolean isSipURI() {
		return true;
	}

	/** {@inheritDoc} */
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
		return new java.util.LinkedHashSet<>(parameters.keySet());
	}

	/** {@inheritDoc} */
	@Override
	public DummySipURI clone() {
		return new DummySipURI(this.toString());
	}

	/** Two URIs are equal when they render identically. */
	@Override
	public boolean equals(Object other) {
		return other instanceof DummySipURI && other.toString().equals(this.toString());
	}

	/** {@inheritDoc} */
	@Override
	public int hashCode() {
		return toString().hashCode();
	}

	/** {@inheritDoc} */
	@Override
	public String getUser() {
		return user;
	}

	/** {@inheritDoc} */
	@Override
	public void setUser(String user) {
		this.user = user;
	}

	/** {@inheritDoc} */
	@Override
	public String getUserPassword() {
		return userPassword;
	}

	/** {@inheritDoc} */
	@Override
	public void setUserPassword(String userPassword) {
		this.userPassword = userPassword;
	}

	/** {@inheritDoc} */
	@Override
	public String getHost() {
		return host;
	}

	/** {@inheritDoc} */
	@Override
	public void setHost(String host) {
		this.host = host;
	}

	/** {@inheritDoc} */
	@Override
	public int getPort() {
		return port;
	}

	/** {@inheritDoc} */
	@Override
	public void setPort(int port) {
		this.port = port;
	}

	/** True when the scheme is {@code sips}. */
	@Override
	public boolean isSecure() {
		return "sips".equalsIgnoreCase(scheme);
	}

	/** {@inheritDoc} */
	@Override
	public void setSecure(boolean secure) {
		this.scheme = secure ? "sips" : "sip";
	}

	/** {@inheritDoc} */
	@Override
	public String getTransportParam() {
		return parameters.get("transport");
	}

	/** {@inheritDoc} */
	@Override
	public void setTransportParam(String transport) {
		parameters.put("transport", transport);
	}

	/** {@inheritDoc} */
	@Override
	public String getMAddrParam() {
		return parameters.get("maddr");
	}

	/** {@inheritDoc} */
	@Override
	public void setMAddrParam(String maddr) {
		parameters.put("maddr", maddr);
	}

	/** {@inheritDoc} */
	@Override
	public String getMethodParam() {
		return parameters.get("method");
	}

	/** {@inheritDoc} */
	@Override
	public void setMethodParam(String method) {
		parameters.put("method", method);
	}

	/** Returns the ttl parameter, or -1 when it is absent. */
	@Override
	public int getTTLParam() {
		String ttl = parameters.get("ttl");
		try {
			return ttl == null ? -1 : Integer.parseInt(ttl);
		} catch (NumberFormatException notANumber) {
			return -1;
		}
	}

	/** {@inheritDoc} */
	@Override
	public void setTTLParam(int ttl) {
		parameters.put("ttl", String.valueOf(ttl));
	}

	/** {@inheritDoc} */
	@Override
	public String getUserParam() {
		return parameters.get("user");
	}

	/** {@inheritDoc} */
	@Override
	public void setUserParam(String user) {
		parameters.put("user", user);
	}

	/** {@inheritDoc} */
	@Override
	public boolean getLrParam() {
		return parameters.containsKey("lr");
	}

	/** {@inheritDoc} */
	@Override
	public void setLrParam(boolean lr) {
		if (lr) {
			parameters.put("lr", "");
		} else {
			parameters.remove("lr");
		}
	}

	/** {@inheritDoc} */
	@Override
	public String getHeader(String name) {
		return uriHeaders.get(name);
	}

	/** {@inheritDoc} */
	@Override
	public void setHeader(String name, String value) {
		uriHeaders.put(name, value);
	}

	/** {@inheritDoc} */
	@Override
	public void removeHeader(String name) {
		uriHeaders.remove(name);
	}

	/** {@inheritDoc} */
	@Override
	public Iterator<String> getHeaderNames() {
		return getHeaderNameList().iterator();
	}

	/** {@inheritDoc} */
	@Override
	public List<String> getHeaderNameList() {
		return new ArrayList<>(uriHeaders.keySet());
	}
}
