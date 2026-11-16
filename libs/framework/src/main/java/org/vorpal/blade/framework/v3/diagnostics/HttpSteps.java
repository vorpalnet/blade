package org.vorpal.blade.framework.v3.diagnostics;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
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
/// method and path, or the status code. Its class and line are the code
/// behind it, so the Trace app's Source tab shows that line:
///
/// - a callout (a request this app sent, and its answer): the line that
///   recorded it, beside the send. Its method slot carries what made it, in
///   words (`connector lookup`, `OAuth token`, `transfer webhook`).
/// - the request that came in: the JAX-RS resource method, at the line that
///   was running when it sent the call's SIP ([HttpTraceFilter#claim]).
/// - its answer: the line that resumed an asynchronous response (tpcc answers
///   from a callflow when the call connects), or for a synchronous one, the
///   resource method, which has returned by then (line -1: the viewer marks its
///   declaration).
///
/// The class is the top-level one, the file the line is in: a frame in a
/// lambda or nested class names `Outer$...`, and only `Outer.java` exists.
/// The text is the message as it went on the wire, credentials masked, capped
/// like a SIP message.
///
/// HTTP steps go to the [TraceLog] ring only, never into the call's replicated
/// session: they are often recorded off the call's thread (a response arrives
/// on the HTTP client's), where touching session state takes the call's lock.
public final class HttpSteps {

	public static final String REQUEST = "http-request";
	public static final String RESPONSE = "http-response";

	private HttpSteps() {
	}

	private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	/// A step for a callout of the call `sessionId`: a request this app sent,
	/// or its answer. `direction` is [CallStep#IN] or [CallStep#OUT] from this
	/// app's side; `source` says what made it. Pinned to the line that called
	/// this method, or, when that is a recorder's own helper named `trace`
	/// (`WebhookSender`, `AbstractOAuth2Authentication`), the line that called
	/// the helper.
	public static CallStep step(String sessionId, long epochMillis, String direction, String kind, String label,
			String source, String message) {
		StackWalker.StackFrame caller = WALKER.walk(frames -> frames
				.filter(f -> f.getDeclaringClass() != HttpSteps.class && !"trace".equals(f.getMethodName()))
				.findFirst().orElse(null));
		return step(sessionId, epochMillis, direction, kind, label, caller, source, message);
	}

	/// A step pinned to `frame` (null: no source position), with `method` in
	/// the method slot.
	static CallStep step(String sessionId, long epochMillis, String direction, String kind, String label,
			StackWalker.StackFrame frame, String method, String message) {
		return step(sessionId, epochMillis, direction, kind, label, frame != null ? topLevel(frame.getClassName()) : null,
				method, frame != null ? frame.getLineNumber() : -1, message);
	}

	/// A step pinned to `className` at `line` (-1: the method's declaration).
	static CallStep step(String sessionId, long epochMillis, String direction, String kind, String label,
			String className, String method, int line, String message) {
		return new CallStep(sessionId, epochMillis, 0, direction, kind, label, className, method, line, cap(message));
	}

	static String topLevel(String className) {
		int dollar = className.indexOf('$');
		return dollar < 0 ? className : className.substring(0, dollar);
	}

	/// The JAX-RS resource method on this thread's stack, or null: the
	/// innermost frame whose method carries `@GET`, `@POST`, ... (any annotation
	/// marked `@HttpMethod`), on its class or on an interface or superclass it
	/// inherits the method from.
	static StackWalker.StackFrame resourceFrame() {
		return WALKER.walk(frames -> frames.filter(HttpSteps::isResourceMethod).findFirst().orElse(null));
	}

	/// The line that resumed a JAX-RS asynchronous response, or null when this
	/// thread is not inside `AsyncResponse.resume`: the frame just below the
	/// outermost frame of a class implementing `AsyncResponse`.
	static StackWalker.StackFrame resumerFrame() {
		List<StackWalker.StackFrame> frames = WALKER.walk(s -> s.collect(Collectors.toList()));
		for (int i = frames.size() - 2; i >= 0; i--) {
			if (javax.ws.rs.container.AsyncResponse.class.isAssignableFrom(frames.get(i).getDeclaringClass())) {
				return frames.get(i + 1);
			}
		}
		return null;
	}

	private static boolean isResourceMethod(StackWalker.StackFrame frame) {
		return declaresResourceMethod(frame.getDeclaringClass(), frame.getMethodName());
	}

	private static boolean declaresResourceMethod(Class<?> type, String name) {
		if (type == null || type == Object.class) {
			return false;
		}
		for (Method m : type.getDeclaredMethods()) {
			if (m.getName().equals(name)) {
				for (Annotation a : m.getDeclaredAnnotations()) {
					if (a.annotationType().isAnnotationPresent(javax.ws.rs.HttpMethod.class)) {
						return true;
					}
				}
			}
		}
		for (Class<?> i : type.getInterfaces()) {
			if (declaresResourceMethod(i, name)) {
				return true;
			}
		}
		return declaresResourceMethod(type.getSuperclass(), name);
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
