package org.vorpal.blade.framework.v3.configuration.expressions;

import java.io.Serializable;

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
/// comparison := atom ( '==' | '!=' | '<' | '>' | '<=' | '>=' ) atom
/// atom       := variable | number | string | bareword | boolean
/// variable   := '${' name '}'
/// string     := "'" any-chars-except-quote "'"
/// bareword   := non-operator non-whitespace characters
/// boolean    := 'true' | 'false'
/// ```
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

		@Override
		public boolean eval(Context ctx) {
			Object l = left.value(ctx);
			Object r = right.value(ctx);
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

			// string literal: 'foo bar'
			if (match("'")) {
				int start = pos;
				while (pos < src.length() && src.charAt(pos) != '\'') pos++;
				if (pos >= src.length()) {
					throw new IllegalArgumentException("unterminated string in: " + src);
				}
				String s = src.substring(start, pos);
				pos++;
				return new Literal(s);
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
			return null;
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
}
