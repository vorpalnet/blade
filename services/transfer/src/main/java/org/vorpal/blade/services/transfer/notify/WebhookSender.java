package org.vorpal.blade.services.transfer.notify;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.naming.InitialContext;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.CallStep;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.diagnostics.HttpSteps;
import org.vorpal.blade.framework.v3.diagnostics.TraceLog;
import org.vorpal.blade.framework.v3.transfer.Webhook;

/// POSTs a notice to a [Webhook], off the SIP thread.
///
/// The POST runs on the container's default managed executor, so a slow or
/// dead web service holds up neither the call nor the next event, and the
/// thread belongs to the container rather than to this application (nothing
/// survives a redeploy holding its class loader). Where no managed executor
/// is bound, the POST runs inline with the webhook's short timeout.
///
/// A failed POST is logged and dropped. The event bus is the record; a
/// webhook is a courtesy to a client that cannot read it.
///
/// For a traced call, the POST and its answer join the call's trace, the
/// webhook's credential headers masked.
final class WebhookSender {

	private static final String EXECUTOR_JNDI = "java:comp/DefaultManagedExecutorService";
	private static volatile ExecutorService executor;
	private static volatile boolean looked;

	private WebhookSender() {
	}

	/// `traced` is the call's trace id when the call is traced, else null.
	static void send(Webhook webhook, String json, String traced) {
		if (webhook == null || webhook.getUrl() == null || webhook.getUrl().isEmpty()) {
			return;
		}
		Runnable post = () -> post(webhook, json, traced);
		ExecutorService pool = executor();
		if (pool != null) {
			try {
				pool.execute(post);
				return;
			} catch (Exception e) {
				log().warning("WebhookSender - executor refused the POST (" + e + "); sending inline");
			}
		}
		post.run();
	}

	private static ExecutorService executor() {
		if (!looked) {
			synchronized (WebhookSender.class) {
				if (!looked) {
					try {
						executor = (ExecutorService) new InitialContext().lookup(EXECUTOR_JNDI);
					} catch (Exception e) {
						log().fine("WebhookSender - no managed executor at " + EXECUTOR_JNDI + "; POSTs run inline");
					}
					looked = true;
				}
			}
		}
		return executor;
	}

	static void post(Webhook webhook, String json, String traced) {
		HttpURLConnection http = null;
		boolean sent = false;
		try {
			http = (HttpURLConnection) new URL(webhook.getUrl()).openConnection();
			http.setRequestMethod("POST");
			http.setConnectTimeout(webhook.timeout());
			http.setReadTimeout(webhook.timeout());
			http.setDoOutput(true);
			http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			for (Map.Entry<String, String> header : webhook.getHeaders().entrySet()) {
				http.setRequestProperty(header.getKey(), header.getValue());
			}
			byte[] body = json.getBytes(StandardCharsets.UTF_8);
			http.setFixedLengthStreamingMode(body.length);
			if (traced != null) {
				trace(traced, CallStep.OUT, HttpSteps.REQUEST, "POST " + http.getURL().getPath(),
						requestText(webhook, json));
			}
			sent = true;
			try (OutputStream out = http.getOutputStream()) {
				out.write(body);
			}
			int status = http.getResponseCode();
			String reply = drain(http, traced != null);
			if (traced != null) {
				trace(traced, CallStep.IN, HttpSteps.RESPONSE, String.valueOf(status), responseText(http, reply));
			}
			if (status >= 300) {
				log().warning("WebhookSender - " + webhook.getUrl() + " answered " + status);
			} else {
				log().finer("WebhookSender - " + webhook.getUrl() + " answered " + status);
			}
		} catch (Exception e) {
			log().warning("WebhookSender - POST to " + webhook.getUrl() + " failed: " + e);
			if (traced != null && sent) {
				trace(traced, CallStep.IN, HttpSteps.RESPONSE, "failed", "No response: " + e);
			}
		} finally {
			if (http != null) {
				http.disconnect();
			}
		}
	}

	/// Reads the reply out so the connection can be reused, returning its
	/// first [Callflow#MESSAGE_MAX_CHARS] bytes when `keep` (a traced call),
	/// else null.
	private static String drain(HttpURLConnection http, boolean keep) {
		java.io.ByteArrayOutputStream kept = keep ? new java.io.ByteArrayOutputStream() : null;
		try (InputStream in = (http.getErrorStream() != null) ? http.getErrorStream() : http.getInputStream()) {
			if (in != null) {
				byte[] buf = new byte[1024];
				int n;
				while ((n = in.read(buf)) >= 0) {
					if (kept != null && kept.size() < Callflow.MESSAGE_MAX_CHARS) {
						kept.write(buf, 0, Math.min(n, Callflow.MESSAGE_MAX_CHARS - kept.size()));
					}
				}
			}
		} catch (IOException e) {
			// nothing to read
		}
		return kept != null ? new String(kept.toByteArray(), StandardCharsets.UTF_8) : null;
	}

	private static void trace(String sessionId, String direction, String kind, String label, String text) {
		TraceLog.append(HttpSteps.step(sessionId, System.currentTimeMillis(), direction, kind, label,
				"transfer webhook", text));
	}

	private static String requestText(Webhook webhook, String json) {
		StringBuilder sb = new StringBuilder();
		sb.append("POST ").append(webhook.getUrl()).append('\n');
		HttpSteps.appendHeader(sb, "Content-Type", "application/json; charset=utf-8");
		for (Map.Entry<String, String> header : webhook.getHeaders().entrySet()) {
			HttpSteps.appendHeader(sb, header.getKey(), header.getValue());
		}
		return sb.append('\n').append(json).toString();
	}

	private static String responseText(HttpURLConnection http, String reply) {
		StringBuilder sb = new StringBuilder();
		// Header field 0 is the status line ("HTTP/1.1 200 OK").
		String statusLine = http.getHeaderField(0);
		sb.append(statusLine != null ? statusLine : "HTTP").append('\n');
		http.getHeaderFields().forEach((name, values) -> {
			if (name != null) {
				for (String value : values) {
					HttpSteps.appendHeader(sb, name, value);
				}
			}
		});
		return sb.append('\n').append(reply != null ? reply : "").toString();
	}

	private static Logger log() {
		return SettingsManager.getSipLogger();
	}
}
