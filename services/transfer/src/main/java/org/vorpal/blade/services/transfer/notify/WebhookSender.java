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
final class WebhookSender {

	private static final String EXECUTOR_JNDI = "java:comp/DefaultManagedExecutorService";
	private static volatile ExecutorService executor;
	private static volatile boolean looked;

	private WebhookSender() {
	}

	static void send(Webhook webhook, String json) {
		if (webhook == null || webhook.getUrl() == null || webhook.getUrl().isEmpty()) {
			return;
		}
		Runnable post = () -> post(webhook, json);
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

	static void post(Webhook webhook, String json) {
		HttpURLConnection http = null;
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
			try (OutputStream out = http.getOutputStream()) {
				out.write(body);
			}
			int status = http.getResponseCode();
			drain(http);
			if (status >= 300) {
				log().warning("WebhookSender - " + webhook.getUrl() + " answered " + status);
			} else {
				log().finer("WebhookSender - " + webhook.getUrl() + " answered " + status);
			}
		} catch (Exception e) {
			log().warning("WebhookSender - POST to " + webhook.getUrl() + " failed: " + e);
		} finally {
			if (http != null) {
				http.disconnect();
			}
		}
	}

	/// Reads the reply out so the connection can be reused.
	private static void drain(HttpURLConnection http) {
		try (InputStream in = (http.getErrorStream() != null) ? http.getErrorStream() : http.getInputStream()) {
			if (in != null) {
				byte[] buf = new byte[1024];
				while (in.read(buf) >= 0) {
					// discard
				}
			}
		} catch (IOException e) {
			// nothing to read
		}
	}

	private static Logger log() {
		return SettingsManager.getSipLogger();
	}
}
