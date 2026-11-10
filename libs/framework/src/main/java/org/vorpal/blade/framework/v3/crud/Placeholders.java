package org.vorpal.blade.framework.v3.crud;

import java.util.regex.Pattern;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v3.configuration.Context;

/// `${name}` session variables in an operation's value, regex pattern or
/// JsonPath. In a pattern or path they let one operation find what an
/// earlier one read: a SIPREC stream's label from the metadata, then that
/// label's media section.
///
/// Everywhere in CRUD, a placeholder resolves from the session only. There is
/// no fallback to environment variables or system properties: CRUD output
/// goes on the wire, and a misspelt `${HOME}` must not carry the server's
/// home directory to a carrier. Write `${env:CRUD_NAME}` or `${sys:crud.name}`
/// to mean one; only names so prefixed are read, so a rule cannot reach the
/// server's other settings ([Context#substituteStrict]).
///
/// A value always lands as data, never as syntax:
/// - in a **regex** it is quoted, so a stream id such as
///   `tpwxAA8BSlRaWJiLzzki8g==` matches itself and its `+` is no quantifier;
/// - in a **JsonPath** it is escaped for a single-quoted string, so write the
///   placeholder inside quotes: `@.value == '${keep}'`;
/// - in an **XPath** it becomes a whole string literal, so write the
///   placeholder bare: `//stream[@id = ${streamId}]`. XPath 1.0 has no escape
///   character, so a value holding both quote marks is built with `concat()`.
///
/// If any placeholder has no value the operation is skipped. Left in place,
/// `${keep}` would go out on the wire as text, or be a different regex (or a
/// JsonPath that matches every node, as `noneof ['${keep}']` does).
///
/// No existing pattern changes meaning: in a Java regex, `${` followed by a
/// name is a syntax error, so no working pattern contained one.
final class Placeholders {

	private Placeholders() {
	}

	/// The value with each `${name}` filled in from `vars`, or null if one has
	/// no value.
	static String value(String template, java.util.Map<String, String> vars) {
		if (!present(template)) return template;
		return Context.substituteStrict(template, vars, v -> v);
	}

	/// The value a JSON or SDP operation inserts, built from `template` with
	/// each `${name}` as data, or null if one has no value.
	///
	/// - A JSON template (it starts with `{` or `[`): a value inside a string
	///   is escaped, so in `{"caller":"${fromUser}"}` a caller's quotes stay
	///   inside it; one outside a string goes in as JSON when it is JSON
	///   (`{"media": ${saved}}`) and as a quoted string when it is not. The
	///   result is parsed.
	/// - A template that is exactly one placeholder inserts that value,
	///   parsed when it is JSON: the save-and-restore pattern, where a read
	///   saved an SDP media section and `${saved}` puts it back. No text
	///   surrounds it, so nothing can break out of anything.
	/// - Anything else is text, never parsed.
	static com.fasterxml.jackson.databind.JsonNode json(String template, java.util.Map<String, String> vars) {
		String trimmed = (template == null) ? "" : template.trim();
		boolean jsonTemplate = trimmed.startsWith("{") || trimmed.startsWith("[");
		boolean single = PLACEHOLDER.matcher(trimmed).matches();
		String resolved = !present(template) ? template
				: jsonTemplate ? Context.substituteStrictAt(template, vars, (at, v) -> jsonValue(template, at, v))
				: Context.substituteStrict(template, vars, v -> v);
		if (resolved == null) return null;
		if (jsonTemplate || single) {
			Object parsed = MessageHelper.jsonOrString(resolved);
			if (parsed instanceof com.fasterxml.jackson.databind.JsonNode) return (com.fasterxml.jackson.databind.JsonNode) parsed;
		}
		return com.fasterxml.jackson.databind.node.TextNode.valueOf(resolved);
	}

	/// How the JSON operations read and write: a path that finds nothing is
	/// nothing to do, not a failure that undoes the rule (as for SDP).
	static final com.jayway.jsonpath.Configuration JSON_PATHS = com.jayway.jsonpath.Configuration.builder()
			.options(com.jayway.jsonpath.Option.SUPPRESS_EXCEPTIONS).build();

	/// The body as JSON, or null when it is not JSON: nothing for a JSON
	/// operation to do (logged at FINE), not a failure that undoes the rule.
	static com.jayway.jsonpath.DocumentContext parseJson(SipServletMessage msg, String operation, String json) {
		try {
			return com.jayway.jsonpath.JsonPath.using(JSON_PATHS).parse(json);
		} catch (RuntimeException notJson) {
			org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger().fine(msg,
					operation + " - the body is not JSON; nothing to do");
			return null;
		}
	}

	/// The body as XML, or null when it is not XML (see [#parseJson]).
	static org.w3c.dom.Document parseXml(SipServletMessage msg, String operation, String xml) {
		try {
			return XmlHelper.parse(xml);
		} catch (Exception notXml) {
			org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger().fine(msg,
					operation + " - the body is not XML; nothing to do");
			return null;
		}
	}

	/// `value` (a Map or List JsonPath read) as JSON text.
	static String toJson(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception e) {
			return String.valueOf(value);
		}
	}

	/// `node` as plain Java values (String, Number, Boolean, Map, List), which
	/// JsonPath's default provider writes as JSON; a Jackson node it would
	/// write as nested arrays.
	static Object plain(com.fasterxml.jackson.databind.JsonNode node) {
		return JSON.convertValue(node, Object.class);
	}

	/// Fails on text after a JSON value, so `true,"admin":true` is not JSON.
	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
			.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	/// A value for the placeholder at `at` in JSON `template`: escaped when
	/// it lands inside a string; outside one, the value itself when it is
	/// JSON (`{"media": ${saved}}`), else a quoted string, so text is never
	/// read as JSON syntax.
	private static String jsonValue(String template, int at, String value) {
		if (insideString(template, at)) return jsonEscape(value);
		if (value.trim().isEmpty()) return "\"" + jsonEscape(value) + "\""; // empty is no JSON value
		try {
			JSON.readTree(value);
			return value;
		} catch (Exception notJson) {
			return "\"" + jsonEscape(value) + "\"";
		}
	}

	/// True when position `at` of JSON text is inside a string literal.
	private static boolean insideString(String json, int at) {
		boolean inside = false;
		for (int i = 0; i < at && i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '\\' && inside) i++;
			else if (c == '"') inside = !inside;
		}
		return inside;
	}

	/// `value` escaped for the inside of a JSON string, without the quotes.
	static String jsonEscape(String value) {
		String quoted = com.fasterxml.jackson.databind.node.TextNode.valueOf(value).toString();
		return quoted.substring(1, quoted.length() - 1);
	}

	/// Logs, at FINE, that `operation` was skipped because a placeholder in
	/// `template` (its value, pattern or path, named by `where`) has no value.
	/// The line names each unset placeholder, so the preview's log tells the
	/// operator which variable to set. `vars` is what the template resolved
	/// against; null means the session's variables.
	static void skipped(SipServletMessage msg, String operation, String where, String template,
			java.util.Map<String, String> vars) {
		if (vars == null) vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
		org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger().fine(msg,
				operation + " - " + unset(template, vars) + " in " + where + " has no value; skipped");
	}

	/// The placeholders in `template` that resolve to nothing, as `${a}, ${b}`.
	static String unset(String template, java.util.Map<String, String> vars) {
		java.util.StringJoiner names = new java.util.StringJoiner(", ");
		if (template != null) {
			for (java.util.regex.Matcher m = PLACEHOLDER.matcher(template); m.find();) {
				if (Context.substituteStrict(m.group(), vars, v -> v) == null) names.add(m.group());
			}
		}
		return names.toString();
	}

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]+\\}");

	/// `template` with every placeholder replaced by `stand`, so its syntax
	/// can be checked before any call supplies the values.
	static String standIn(String template, String stand) {
		return PLACEHOLDER.matcher(template).replaceAll(java.util.regex.Matcher.quoteReplacement(stand));
	}

	/// Throws if `pattern` is not a regex. A placeholder becomes a quoted
	/// literal at run time, so `x` stands in for it.
	static void checkRegex(String pattern) {
		if (pattern != null && !pattern.isEmpty()) Pattern.compile(standIn(pattern, "x"), Pattern.DOTALL);
	}

	/// Throws if `expression` is not an XPath. A placeholder becomes a string
	/// literal at run time ([#xpathLiteral]).
	static void checkXPath(String expression) throws javax.xml.xpath.XPathExpressionException {
		if (expression != null && !expression.isEmpty()) {
			javax.xml.xpath.XPathFactory.newInstance().newXPath().compile(standIn(expression, "'x'"));
		}
	}

	/// Throws if `path` is not a JsonPath. A placeholder is written inside
	/// quotes, so `x` stands in for it.
	static void checkJsonPath(String path) {
		if (path == null || path.isEmpty()) return;
		for (java.util.regex.Matcher m = PLACEHOLDER.matcher(path); m.find();) {
			if (quoteAt(path, m.start()) == 0) {
				throw new IllegalArgumentException(m.group() + " is outside quotes: it is compared as text, so write it inside them ('" + m.group() + "')");
			}
		}
		com.jayway.jsonpath.JsonPath.compile(standIn(path, "x"));
	}

	/// Checks each expression of a read operation, naming the variable whose
	/// expression is broken.
	static void checkEach(java.util.Map<String, String> expressions, ExpressionCheck check) throws Exception {
		if (expressions == null) return;
		for (java.util.Map.Entry<String, String> e : expressions.entrySet()) {
			try {
				check.accept(e.getValue());
			} catch (Exception x) {
				throw new IllegalArgumentException(e.getKey() + ": " + x.getMessage(), x);
			}
		}
	}

	@FunctionalInterface
	interface ExpressionCheck {
		void accept(String expression) throws Exception;
	}

	static boolean present(String template) {
		return template != null && template.contains("${");
	}

	/// The regex with each `${name}` replaced by its quoted value, or null if
	/// one has no value.
	static String regex(String pattern, SipServletMessage msg) {
		if (!present(pattern)) return pattern;
		return Context.substituteStrict(pattern, MessageHelper.getSessionVariables(msg.getApplicationSession()),
				Pattern::quote);
	}

	/// The JsonPath with each `${name}` replaced by its value, escaped for a
	/// single-quoted string, or null if one has no value.
	static String jsonPath(String path, SipServletMessage msg) {
		if (!present(path)) return path;
		return Context.substituteStrictAt(path, MessageHelper.getSessionVariables(msg.getApplicationSession()),
				(at, value) -> jsonPathValue(path, at, value));
	}

	/// A value for the placeholder at `at` in a JsonPath: escaped for the
	/// quotes it sits in, single or double, or, outside quotes, made a
	/// single-quoted string, so a caller's text can never become filter
	/// syntax (`x" || @.label != "x` would match every stream).
	static String jsonPathValue(String path, int at, String value) {
		String escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
		return (quoteAt(path, at) != 0) ? escaped : "'" + escaped + "'";
	}

	/// The quote character (`'` or `"`) position `at` of `text` sits inside,
	/// or 0 when it is outside any.
	static char quoteAt(String text, int at) {
		char open = 0;
		for (int i = 0; i < at && i < text.length(); i++) {
			char c = text.charAt(i);
			if (open != 0 && c == '\\') i++;
			else if (open == 0 && (c == '\'' || c == '"')) open = c;
			else if (c == open) open = 0;
		}
		return open;
	}

	/// The XPath with each `${name}` replaced by its value as a string
	/// literal, or null if one has no value.
	static String xpath(String expression, SipServletMessage msg) {
		if (!present(expression)) return expression;
		return Context.substituteStrict(expression,
				MessageHelper.getSessionVariables(msg.getApplicationSession()), Placeholders::xpathLiteral);
	}

	/// `value` as an XPath 1.0 string literal.
	static String xpathLiteral(String value) {
		if (value.indexOf('\'') < 0) return "'" + value + "'";
		if (value.indexOf('"') < 0) return "\"" + value + "\"";
		StringBuilder concat = new StringBuilder("concat(");
		String[] pieces = value.split("'", -1);
		for (int i = 0; i < pieces.length; i++) {
			if (i > 0) concat.append(", \"'\", ");
			concat.append('\'').append(pieces[i]).append('\'');
		}
		return concat.append(')').toString();
	}

	/// Compiles `pattern` now if it has no placeholders, so a broken one is
	/// reported when the configuration loads; otherwise null, and it is
	/// compiled per message.
	static Pattern compileStatic(String pattern) {
		return (pattern != null && !present(pattern)) ? Pattern.compile(pattern, Pattern.DOTALL) : null;
	}

	/// The pattern for this message: `cached` when the pattern has no
	/// placeholders, else compiled with the session's values; null when one
	/// has no value.
	static Pattern compileFor(String pattern, Pattern cached, SipServletMessage msg) {
		if (!present(pattern)) return (cached != null || pattern == null) ? cached : compileStatic(pattern);
		String resolved = regex(pattern, msg);
		return (resolved != null) ? Pattern.compile(resolved, Pattern.DOTALL) : null;
	}
}
