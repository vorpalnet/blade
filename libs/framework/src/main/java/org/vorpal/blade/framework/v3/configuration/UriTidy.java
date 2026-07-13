package org.vorpal.blade.framework.v3.configuration;

/// Repairs SIP URI and address strings before they are handed to
/// `sipFactory.createURI()` / `createAddress()`.
///
/// Two damage sources feed this class:
///
/// 1. **Template substitution.** When a `${variable}` resolves to empty, the
///    template's literal delimiter is left orphaned — e.g.
///    `"${proto}:carol@${host}:5060;${uriparams}"` with no params yields
///    `"sip:carol@host:5060;"`, an empty `${proto}` yields `":carol@host"`,
///    and an empty `${displayname}` yields `"" <sip:carol@host>`.
/// 2. **Hand-authored configuration.** Administrators write addresses with a
///    missing scheme, missing or unbalanced angle brackets, unquoted
///    multi-word display names, mismatched or typographic ("smart") quotes,
///    and stray whitespace.
///
/// [#tidy] normalizes both. The address layer extracts the display name and
/// bracketed URI in whatever mangled form they arrive, then the URI layer
/// removes orphaned delimiters (`;;`, trailing `;`, empty `:` port, empty
/// `@` user), supplies a missing scheme (`tel:` when the string starts with
/// `+`, else `sip:`), and strips the SIP default port (5060, or 5061 for
/// `sips:`/`transport=tls`). The result is reassembled canonically:
/// `"Display Name" <uri>` when a display name is present, `<uri>` when the
/// input was bracketed, and a bare URI otherwise — so a Request-URI stays a
/// bare URI and a To header keeps its form. Pure string logic, no SIP
/// dependencies — born in v3, to earn its way down to the base layer later.
public final class UriTidy {

	private UriTidy() {
	}

	/// Tidy a SIP URI or address string. Returns the input unchanged if it is
	/// `null`, blank, or contains no recoverable URI.
	public static String tidy(String address) {
		if (address == null) {
			return null;
		}

		// Typographic quotes -> ASCII, so values pasted from Word/Outlook parse.
		String s = address.replaceAll("[“”„‟]", "\"").replaceAll("[‘’‚‛]", "'")
				.trim();
		if (s.isEmpty()) {
			return address;
		}

		// -------- address layer: display name and angle brackets --------

		String display = null;
		String rest = s;

		// Leading quoted display name; double quotes honor backslash escapes.
		if (rest.startsWith("\"")) {
			int close = closingQuote(rest);
			if (close > 0) {
				display = unescape(rest.substring(1, close)).trim();
				rest = rest.substring(close + 1).trim();
			} else {
				// Unbalanced opening quote: the name runs to the '<' if there is one.
				int lt = rest.indexOf('<');
				if (lt >= 0) {
					display = rest.substring(1, lt).trim();
					rest = rest.substring(lt);
				} else {
					rest = rest.substring(1);
				}
			}
		} else if (rest.startsWith("'")) {
			int close = rest.indexOf('\'', 1);
			if (close > 0) {
				display = rest.substring(1, close).trim();
				rest = rest.substring(close + 1).trim();
			}
		}

		// Angle brackets: take the inside; anything before '<' is a display name.
		boolean hadBrackets = false;
		String uri;
		int lt = rest.indexOf('<');
		if (lt >= 0) {
			hadBrackets = true;
			String before = rest.substring(0, lt).trim();
			if (display == null && !before.isEmpty()) {
				display = stripQuotes(before);
			}
			int gt = rest.lastIndexOf('>');
			uri = (gt > lt) ? rest.substring(lt + 1, gt) : rest.substring(lt + 1);
		} else {
			int gt = rest.lastIndexOf('>');
			if (gt >= 0) {
				hadBrackets = true;
				uri = rest.substring(0, gt);
			} else {
				uri = rest;
			}
		}
		uri = uri.trim();

		// Unbracketed (or bracket-enclosed) display name: "Bob Smith sip:bob@host".
		// The tail after the last whitespace is the URI if it looks like one and
		// the head doesn't (a head with ':' or '@' is a URI broken by whitespace).
		if (display == null) {
			int ws = lastWhitespace(uri);
			if (ws >= 0) {
				String head = uri.substring(0, ws).trim();
				String tail = uri.substring(ws + 1);
				boolean tailIsUri = tail.indexOf(':') >= 0 || tail.indexOf('@') >= 0;
				boolean headIsUriFragment = head.indexOf(':') >= 0 || head.indexOf('@') >= 0;
				if (tailIsUri && !headIsUriFragment) {
					display = stripQuotes(head);
					uri = tail;
				}
			}
		}

		// The whole input was a quoted URI: "sip:bob@host" with nothing after it.
		if (uri.isEmpty() && display != null && (display.indexOf(':') >= 0 || display.indexOf('@') >= 0)) {
			uri = display;
			display = null;
		}

		// A single-quote pair wrapping the URI is admin quoting; interior single
		// quotes are legal userinfo characters (o'brien@host) and are kept.
		if (uri.length() >= 2 && uri.startsWith("'") && uri.endsWith("'")) {
			uri = uri.substring(1, uri.length() - 1);
		}
		// Double quotes and whitespace are never legal inside a URI.
		uri = uri.replaceAll("[\"\\s]+", "");

		if (uri.isEmpty()) {
			return address; // nothing recoverable — let createURI() report it
		}

		// -------- URI layer --------

		// Missing scheme: an empty ${proto} leaves ":carol@host"; a hand-written
		// value may have no scheme at all. '+' means a phone number -> tel:.
		if (uri.startsWith(":")) {
			uri = uri.substring(1);
		}
		if (!uri.regionMatches(true, 0, "sip:", 0, 4) && !uri.regionMatches(true, 0, "sips:", 0, 5)
				&& !uri.regionMatches(true, 0, "tel:", 0, 4)) {
			uri = (uri.startsWith("+") ? "tel:" : "sip:") + uri;
		}

		uri = tidyUri(uri);

		// -------- reassemble --------

		if (display != null && !display.isEmpty()) {
			return '"' + escape(display) + "\" <" + uri + '>';
		}
		return hadBrackets ? "<" + uri + '>' : uri;
	}

	/// The original delimiter/port rules, applied to a bare (bracket-free) URI.
	private static String tidyUri(String uri) {
		String s = uri;

		// Collapse runs of ';' left by adjacent empty params (e.g. ";;" -> ";").
		s = s.replaceAll(";{2,}", ";");

		// Drop a single trailing ';'.
		if (s.endsWith(";")) {
			s = s.substring(0, s.length() - 1);
		}

		// Empty port: a ':' with no port digits (followed by ';', '?', or end).
		// The scheme colon ("sip:") is followed by content, so it is never matched.
		s = s.replaceAll(":(?=[;?]|$)", "");

		// Empty user: scheme colon directly followed by '@' ("sip:@host" -> "sip:host").
		s = s.replaceAll("(?i)(sips?):@", "$1:");

		// Strip the SIP default port: 5061 for sips:/transport=tls, else 5060.
		// A non-default explicit port (e.g. :5070, or :5060 on a sips: URI) is kept.
		String lower = s.toLowerCase();
		boolean secure = lower.contains("sips:") || lower.contains("transport=tls");
		String defaultPort = secure ? "5061" : "5060";
		s = s.replaceAll(":" + defaultPort + "(?=[;?]|$)", "");

		return s;
	}

	/// Index of the closing unescaped '"' in a string that starts with '"', or -1.
	private static int closingQuote(String s) {
		for (int i = 1; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				i++;
			} else if (c == '"') {
				return i;
			}
		}
		return -1;
	}

	private static int lastWhitespace(String s) {
		for (int i = s.length() - 1; i >= 0; i--) {
			if (Character.isWhitespace(s.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	/// Strip stray quote characters from the ends of a display name.
	private static String stripQuotes(String s) {
		return s.replaceAll("^[\"']+|[\"']+$", "").trim();
	}

	private static String unescape(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				b.append(s.charAt(++i));
			} else {
				b.append(c);
			}
		}
		return b.toString();
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
