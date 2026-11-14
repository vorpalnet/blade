package org.vorpal.blade.framework.v3.diagnostics;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v3.CallStep;
import org.vorpal.blade.framework.v3.Callflow;

/// The HTTP messages of a traced call, as [CallStep]s beside its SIP ones: the
/// REST request that placed the call and its answer ([HttpTraceFilter]), the
/// callouts a REST connector makes while routing it, the OAuth token fetches
/// those callouts need, and the transfer service's webhooks.
///
/// An HTTP step's `kind` is [#REQUEST] or [#RESPONSE]; its `label` is the
/// method and path, or the status code. It names no class or source line,
/// since no callflow line sent it. In its method slot it carries what made it,
/// in words (`connector lookup`, `OAuth token`, `transfer webhook`), or null
/// for the request that came in. The text is the message as it went on the
/// wire, credentials masked, capped like a SIP message.
///
/// HTTP steps go to the [TraceLog] ring only, never into the call's replicated
/// session: they are often recorded off the call's thread (a response arrives
/// on the HTTP client's), where touching session state takes the call's lock.
public final class HttpSteps {

	public static final String REQUEST = "http-request";
	public static final String RESPONSE = "http-response";

	private HttpSteps() {
	}

	/// A step for an HTTP request or response of the call `sessionId`.
	/// `direction` is [CallStep#IN] or [CallStep#OUT] from this app's side;
	/// `source` says what made it, or is null for a request that came in.
	public static CallStep step(String sessionId, long epochMillis, String direction, String kind, String label,
			String source, String message) {
		return new CallStep(sessionId, epochMillis, 0, direction, kind, label, null, source, -1, cap(message));
	}

	/// A header whose value is a credential: its name says authorization, key,
	/// token, secret, signature or cookie (`Authorization`, `X-API-Key`,
	/// `Set-Cookie`, ...). A trace is shown in a browser and saved in snapshots,
	/// and a live credential there is as good as the credential itself.
	public static boolean isCredentialHeader(String name) {
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.contains("auth") || lower.contains("key") || lower.contains("token")
				|| lower.contains("secret") || lower.contains("signature") || lower.contains("cookie");
	}

	/// One header line, its value masked when it is a credential.
	public static void appendHeader(StringBuilder sb, String name, String value) {
		sb.append(name).append(": ").append(isCredentialHeader(name) ? "********" : value).append('\n');
	}

	private static final Pattern FORM_FIELD = Pattern.compile("([^&=]+)=([^&]*)");
	private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"([^\"]+)\"(\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");

	/// A form or JSON field whose value is a credential, as a token endpoint
	/// exchanges them: `client_secret`, `password`, `assertion`,
	/// `refresh_token`, `access_token`, `id_token`, ...
	static boolean isCredentialField(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return isCredentialHeader(lower) || lower.contains("password") || lower.contains("assertion")
				|| lower.contains("credential");
	}

	/// A form body (`grant_type=password&username=…&password=…`) with each
	/// credential field's value masked. For a token endpoint's request, whose
	/// body is all about credentials; not for bodies in general, where a field
	/// named like `sessionKeys` is data.
	public static String maskForm(String body) {
		return mask(FORM_FIELD, body, m -> m.group(1) + "=********");
	}

	/// A JSON body with each credential field's string value masked: a token
	/// endpoint's answer.
	public static String maskJson(String body) {
		return mask(JSON_STRING_FIELD, body, m -> "\"" + m.group(1) + "\"" + m.group(2) + "\"********\"");
	}

	private static String mask(Pattern pattern, String body, java.util.function.Function<Matcher, String> masked) {
		if (body == null) {
			return null;
		}
		Matcher m = pattern.matcher(body);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String name = m.group(1);
			m.appendReplacement(sb, Matcher.quoteReplacement(isCredentialField(name) ? masked.apply(m) : m.group()));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	static String cap(String text) {
		if (text != null && text.length() > Callflow.MESSAGE_MAX_CHARS) {
			return text.substring(0, Callflow.MESSAGE_MAX_CHARS) + "\n… [truncated]";
		}
		return text;
	}
}
