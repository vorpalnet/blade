package org.vorpal.blade.framework.v3.configuration.expressions;

import java.io.Serializable;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

/// A compiled boolean expression evaluated against a [Context].
///
/// Intentionally small and safe-by-construction: the grammar allows
/// variable lookups, literal values, comparisons, and boolean
/// combinators — nothing else. No arbitrary method invocation, no
/// scripting, no surprises. Expressions that fail to parse throw
/// [IllegalArgumentException] at construction so config-load errors
/// surface early.
///
/// ## Grammar
///
/// ```
/// expr       := or_expr
/// or_expr    := and_expr ( '||' and_expr )*
/// and_expr   := not_expr ( '&&' not_expr )*
/// not_expr   := '!' not_expr | primary
/// primary    := '(' expr ')' | comparison | atom
/// comparison := atom ( '==' | '!=' | '<' | '>' | '<=' | '>='
///                    | 'matches' | 'contains' | 'insubnet' ) atom
/// atom       := variable | number | string | bareword | boolean
/// variable   := '${' name '}'
/// string     := "'" ( "\\'" | any-char-except-quote )* "'"
/// bareword   := non-operator non-whitespace characters
/// boolean    := 'true' | 'false'
/// ```
///
/// `matches` is a full-string regex match (Java [java.util.regex.Pattern]
/// syntax); `contains` is a literal substring test. Both treat an
/// unresolved left side as the empty string. A malformed regex pattern
/// evaluates to false rather than throwing.
///
/// `insubnet` is true when the left side (an IP address) falls inside the
/// right side, a CIDR block or bare IP — e.g.
/// `${originIP} insubnet '10.20.0.0/16'`. Real subnet math via the
/// `ipaddress` library: any CIDR boundary (/18, /26, …), IPv4 and IPv6; a
/// bare IP on the right means exact match. Unparseable input → false.
///
/// ## Value semantics
///
/// - **`${name}`** resolves against the Context using the same
///   fallback chain as templates: SipSession → SipApplicationSession →
///   environment variable → system property. Unresolved → empty string
///   (boolean coerces to false, comparison coerces to "").
/// - **Numbers**: when both sides of a comparison parse as numbers,
///   numeric comparison is used. Otherwise lexicographic string
///   comparison.
/// - **Boolean coercion** (for `!`, `&&`, `||`, or bare-atom boolean
///   context): the strings `"true"`, `"1"`, `"yes"` (case-insensitive)
///   resolve to true; everything else (including null and empty) to
///   false.
///
/// ## Examples
///
/// ```
/// ${score} > 80
/// ${action} == allow
/// ${action} == allow && ${score} >= 50
/// ${override} == true || ${emergencyMode} == true
/// !${blocked}
/// ${description} == 'not allowed'
/// (${customerTier} == premium && ${shift} == business) || ${override}
/// ${To.host} contains 'example'
/// ${From.user} matches '1\d{10}'
/// ${originIP} insubnet '10.20.0.0/16'
/// ```
public class Expression implements Serializable {
	private static final long serialVersionUID = 1L;

	private final String source;
	private final Node root;

	public Expression(String source) {
		if (source == null) throw new IllegalArgumentException("expression is null");
		this.source = source;
		this.root = new Parser(source).parse();
	}

	public boolean evaluate(Context ctx) {
		return root.eval(ctx);
	}

	public String getSource() {
		return source;
	}

	@Override
	public String toString() {
		return source;
	}

	// ---- AST nodes ----

	private interface Node extends Serializable {
		boolean eval(Context ctx);
		Object value(Context ctx);
	}

	private static final class Literal implements Node {
		private static final long serialVersionUID = 1L;
		private final Object value;

		Literal(Object value) {
			this.value = value;
		}

		@Override
		public Object value(Context ctx) {
			return value;
		}

		@Override
		public boolean eval(Context ctx) {
			return toBool(value);
		}

		/// Renders the literal the way it was written, so a FINEST condition
		/// trace reads like the config: a string in quotes, everything else bare.
		@Override
		public String toString() {
			return (value instanceof String) ? "'" + value + "'" : String.valueOf(value);
		}
	}

	private static final class Variable implements Node {
		private static final long serialVersionUID = 1L;
		private final String name;

		Variable(String name) {
			this.name = name;
		}

		@Override
		public Object value(Context ctx) {
			if (ctx == null) return null;
			// Use resolve so we get the full fallback chain (session, env,
			// sysprop). For unresolved names, resolve returns the literal
			// template which is fine for our fallback-to-false semantics.
			String resolved = ctx.resolve("${" + name + "}");
			// If resolve returned the literal template unchanged, treat
			// as unresolved (null) so comparisons and boolean coercion
			// behave sensibly.
			if (resolved != null && resolved.equals("${" + name + "}")) return null;
			return resolved;
		}

		@Override
		public boolean eval(Context ctx) {
			return toBool(value(ctx));
		}

		/// Renders as it was written, `${name}`, so a FINEST trace names the
		/// variable a reader can go find in the config.
		@Override
		public String toString() {
			return "${" + name + "}";
		}
	}

	private static final class Compare implements Node {
		private static final long serialVersionUID = 1L;
		private final Node left;
		private final Node right;
		private final String op;

		Compare(Node left, String op, Node right) {
			this.left = left;
			this.op = op;
			this.right = right;
		}

		@Override
		public Object value(Context ctx) {
			return eval(ctx);
		}

		/// Lazily compiled regex for the `matches` operator with the source it was
		/// compiled from, held as one immutable pair so a dynamic (variable) right
		/// side recompiles only when it changes. A config's expressions are shared
		/// by every call thread; two separate fields could be read half-updated,
		/// pairing one call's source with another call's pattern. Transient,
		/// recompiled after deserialization.
		///
		/// A variable right side makes the pattern whatever that variable holds.
		/// Never let it hold caller data: a caller-chosen regex can be made to run
		/// for seconds, or to match anything.
		private transient volatile Cached<java.util.regex.Pattern> cachedPattern;

		/// Lazily parsed subnet/IP for the `insubnet` operator, same pairing.
		private transient volatile Cached<inet.ipaddr.IPAddress> cachedSubnet;

		@Override
		public boolean eval(Context ctx) {
			Object l = left.value(ctx);
			Object r = right.value(ctx);
			boolean result = compute(l, r);
			explain(l, result);
			return result;
		}

		/// The comparison itself, unchanged. Split out from [#eval] so [#explain]
		/// can trace the decision on the exact values it was made from.
		private boolean compute(Object l, Object r) {
			// String operators: no numeric coercion.
			if ("matches".equals(op) || "contains".equals(op)) {
				String ls = (l == null) ? "" : l.toString();
				String rs = (r == null) ? "" : r.toString();
				if ("contains".equals(op)) {
					return ls.contains(rs);
				}
				try {
					Cached<java.util.regex.Pattern> cached = cachedPattern;
					if (cached == null || !rs.equals(cached.source)) {
						cached = new Cached<>(rs, java.util.regex.Pattern.compile(rs, java.util.regex.Pattern.DOTALL));
						cachedPattern = cached;
					}
					java.util.regex.Pattern pattern = cached.value;
					// "Any instance" semantics: a value carrying the multi-value
					// delimiter (a repeating header read by an allInstances
					// selector) matches if ANY of its instances matches. An
					// ordinary single value has no delimiter — one element,
					// identical to a plain full-string match — so iRouter and
					// every existing condition are unaffected.
					if (!ls.contains(Context.MULTI_VALUE_DELIMITER)) {
						return pattern.matcher(ls).matches();
					}
					for (String element : ls.split(
							java.util.regex.Pattern.quote(Context.MULTI_VALUE_DELIMITER), -1)) {
						if (pattern.matcher(element).matches()) {
							return true;
						}
					}
					return false;
				} catch (java.util.regex.PatternSyntaxException e) {
					// Malformed pattern: evaluate to false, never throw mid-routing.
					return false;
				}
			}

			// CIDR containment: left IP inside the right CIDR block (or equal
			// to the right bare IP). Backed by the ipaddress library — real
			// subnet math, any boundary, IPv4 and IPv6. toPrefixBlock() ignores
			// host bits in the CIDR. Any parse failure → false (never throw).
			if ("insubnet".equals(op)) {
				String ls = (l == null) ? "" : l.toString();
				String rs = (r == null) ? "" : r.toString();
				try {
					Cached<inet.ipaddr.IPAddress> cached = cachedSubnet;
					if (cached == null || !rs.equals(cached.source)) {
						inet.ipaddr.IPAddress net = new inet.ipaddr.IPAddressString(rs).getAddress();
						cached = new Cached<>(rs, (net != null) ? net.toPrefixBlock() : null);
						cachedSubnet = cached;
					}
					inet.ipaddr.IPAddress subnet = cached.value;
					inet.ipaddr.IPAddress ip = new inet.ipaddr.IPAddressString(ls).getAddress();
					return subnet != null && ip != null && subnet.contains(ip);
				} catch (Exception e) {
					return false;
				}
			}

			Double ln = asNumber(l);
			Double rn = asNumber(r);
			if (ln != null && rn != null) {
				double d = ln - rn;
				switch (op) {
					case "==": return d == 0;
					case "!=": return d != 0;
					case "<":  return d < 0;
					case ">":  return d > 0;
					case "<=": return d <= 0;
					case ">=": return d >= 0;
					default:   throw new IllegalStateException("unknown op: " + op);
				}
			}
			String ls = (l == null) ? "" : l.toString();
			String rs = (r == null) ? "" : r.toString();
			int c = ls.compareTo(rs);
			switch (op) {
				case "==": return c == 0;
				case "!=": return c != 0;
				case "<":  return c < 0;
				case ">":  return c > 0;
				case "<=": return c <= 0;
				case ">=": return c >= 0;
				default:   throw new IllegalStateException("unknown op: " + op);
			}
		}

		/// Says, at FINEST, why this comparison came out the way it did: the
		/// condition as written, the value the left side actually resolved to,
		/// and the result. The point is the case that fooled everyone here — a
		/// condition that reads right but quietly evaluates false. The two ways
		/// that happens are called out by name:
		///
		/// - the variable is UNSET (no selector stored it), so it reads as the
		///   empty string and can never match — the silent-drop this exists for;
		/// - the variable resolved to a value that simply is not what the
		///   operator wanted (e.g. `${To.user}` is `+14153760002`, but the
		///   pattern `14153760002` demands a full-string match and the `+` is a
		///   carrier's E.164 prefix that a request-URI user usually lacks).
		///
		/// Costs nothing until someone raises the level to FINEST. Never throws:
		/// a diagnostic must not break the routing decision it is reporting on.
		private void explain(Object l, boolean result) {
			Logger log = SettingsManager.getSipLogger();
			if (log == null || !log.isLoggable(Level.FINEST)) return;
			String why = (l == null)
					? left + " is UNSET (no selector stored it), so it reads as empty \"\""
					: left + " resolved to \"" + l + "\"";
			log.finest("when " + left + " " + op + " " + right + "  ->  " + result + "   (" + why + ")");
		}
	}

	private static final class And implements Node {
		private static final long serialVersionUID = 1L;
		private final Node left;
		private final Node right;

		And(Node left, Node right) {
			this.left = left;
			this.right = right;
		}

		@Override
		public Object value(Context ctx) {
			return eval(ctx);
		}

		@Override
		public boolean eval(Context ctx) {
			return left.eval(ctx) && right.eval(ctx);
		}
	}

	private static final class Or implements Node {
		private static final long serialVersionUID = 1L;
		private final Node left;
		private final Node right;

		Or(Node left, Node right) {
			this.left = left;
			this.right = right;
		}

		@Override
		public Object value(Context ctx) {
			return eval(ctx);
		}

		@Override
		public boolean eval(Context ctx) {
			return left.eval(ctx) || right.eval(ctx);
		}
	}

	private static final class Not implements Node {
		private static final long serialVersionUID = 1L;
		private final Node operand;

		Not(Node operand) {
			this.operand = operand;
		}

		@Override
		public Object value(Context ctx) {
			return eval(ctx);
		}

		@Override
		public boolean eval(Context ctx) {
			return !operand.eval(ctx);
		}
	}

	// ---- value helpers ----

	private static boolean toBool(Object o) {
		if (o == null) return false;
		if (o instanceof Boolean) return (Boolean) o;
		if (o instanceof Number) return ((Number) o).doubleValue() != 0;
		String s = o.toString();
		return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
	}

	private static Double asNumber(Object o) {
		if (o == null) return null;
		if (o instanceof Number) return ((Number) o).doubleValue();
		String s = o.toString();
		if (s.isEmpty()) return null;
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---- recursive-descent parser ----

	private static final class Parser {
		private final String src;
		private int pos;

		Parser(String src) {
			this.src = src;
			this.pos = 0;
		}

		Node parse() {
			Node node = parseOr();
			skipWhitespace();
			if (pos < src.length()) {
				throw new IllegalArgumentException(
						"unexpected trailing content at position " + pos + ": " + src);
			}
			return node;
		}

		private Node parseOr() {
			Node left = parseAnd();
			while (match("||")) {
				Node right = parseAnd();
				left = new Or(left, right);
			}
			return left;
		}

		private Node parseAnd() {
			Node left = parseNot();
			while (match("&&")) {
				Node right = parseNot();
				left = new And(left, right);
			}
			return left;
		}

		private Node parseNot() {
			skipWhitespace();
			// A bare '!' is a unary operator; '!=' is a comparison we
			// leave for parsePrimary/parseCompareOp to recognize.
			if (pos < src.length() && src.charAt(pos) == '!'
					&& (pos + 1 >= src.length() || src.charAt(pos + 1) != '=')) {
				pos++;
				return new Not(parseNot());
			}
			return parsePrimary();
		}

		private Node parsePrimary() {
			skipWhitespace();
			if (match("(")) {
				Node inner = parseOr();
				if (!match(")")) {
					throw new IllegalArgumentException(
							"expected ')' at position " + pos + " in: " + src);
				}
				return inner;
			}
			Node left = parseAtom();
			skipWhitespace();
			String op = parseCompareOp();
			if (op != null) {
				Node right = parseAtom();
				return new Compare(left, op, right);
			}
			return left;
		}

		private Node parseAtom() {
			skipWhitespace();
			if (pos >= src.length()) {
				throw new IllegalArgumentException("expected value at end of: " + src);
			}

			// variable: ${name}
			if (match("${")) {
				int start = pos;
				while (pos < src.length() && src.charAt(pos) != '}') pos++;
				if (pos >= src.length()) {
					throw new IllegalArgumentException("unterminated ${…} in: " + src);
				}
				String name = src.substring(start, pos);
				pos++;
				return new Variable(name);
			}

			// string literal: 'foo bar'. \' is an escaped single quote so a
			// literal can contain one (e.g. a display name like O'Brien). Only
			// \' is special — \\, \d, \. and every other backslash pass through
			// verbatim, so regex patterns on the right of `matches` are untouched.
			if (match("'")) {
				StringBuilder sb = new StringBuilder();
				while (pos < src.length()) {
					char c = src.charAt(pos);
					if (c == '\\' && pos + 1 < src.length() && src.charAt(pos + 1) == '\'') {
						sb.append('\'');
						pos += 2;
					} else if (c == '\'') {
						break;
					} else {
						sb.append(c);
						pos++;
					}
				}
				if (pos >= src.length()) {
					throw new IllegalArgumentException("unterminated string in: " + src);
				}
				pos++; // closing quote
				return new Literal(sb.toString());
			}

			// number or bare word
			int start = pos;
			while (pos < src.length() && !isBareTerminator(src.charAt(pos))) {
				pos++;
			}
			if (start == pos) {
				throw new IllegalArgumentException(
						"expected value at position " + pos + " in: " + src);
			}
			String word = src.substring(start, pos);
			// number
			try {
				return new Literal(Double.parseDouble(word));
			} catch (NumberFormatException ignore) {
			}
			// boolean literal
			if ("true".equals(word))  return new Literal(Boolean.TRUE);
			if ("false".equals(word)) return new Literal(Boolean.FALSE);
			// bare string
			return new Literal(word);
		}

		private String parseCompareOp() {
			skipWhitespace();
			if (match("==")) return "==";
			if (match("!=")) return "!=";
			if (match("<=")) return "<=";
			if (match(">=")) return ">=";
			if (match("<"))  return "<";
			if (match(">"))  return ">";
			if (matchWord("matches"))  return "matches";
			if (matchWord("contains")) return "contains";
			if (matchWord("insubnet")) return "insubnet";
			return null;
		}

		/// Like [#match] but only for word operators: the operator must be
		/// followed by a non-bareword character (whitespace, quote, `${`,
		/// `(`, or end of input) so a bareword like `containsX` is not
		/// half-consumed as an operator.
		private boolean matchWord(String s) {
			skipWhitespace();
			if (pos + s.length() > src.length()
					|| !src.regionMatches(pos, s, 0, s.length())) {
				return false;
			}
			int after = pos + s.length();
			if (after < src.length()) {
				char c = src.charAt(after);
				if (!Character.isWhitespace(c) && c != '\'' && c != '$' && c != '(') {
					return false;
				}
			}
			pos += s.length();
			return true;
		}

		private static boolean isBareTerminator(char c) {
			return Character.isWhitespace(c)
					|| c == '=' || c == '!' || c == '<' || c == '>'
					|| c == '&' || c == '|' || c == '(' || c == ')';
		}

		private boolean match(String s) {
			skipWhitespace();
			if (pos + s.length() <= src.length()
					&& src.regionMatches(pos, s, 0, s.length())) {
				pos += s.length();
				return true;
			}
			return false;
		}

		private void skipWhitespace() {
			while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
		}
	}

	/// A parsed value with the text it was parsed from, swapped as one.
	private static final class Cached<T> {
		final String source;
		final T value;

		Cached(String source, T value) {
			this.source = source;
			this.value = value;
		}
	}
}
