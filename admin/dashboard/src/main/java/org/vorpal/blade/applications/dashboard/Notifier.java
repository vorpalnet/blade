package org.vorpal.blade.applications.dashboard;

import java.util.Properties;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Tells someone when trouble starts and when it ends, so nobody has to be
/// watching the board: a problem raised or cleared (an application's, a
/// certificate near expiry, events parked on the bus), an endpoint, trunk or
/// remote service going down or coming back. [OpsFeed] calls it on the
/// change only, never on a repeat, so a notice is one fact.
///
/// Each notice is POSTed as JSON to every webhook, and emailed to every
/// address, as [NotifySettings] says. Sent off the event's thread, on one
/// worker per dashboard, and never retried: a notice lost to a mail server
/// that is down is still on the board. The worker stops with the dashboard
/// ([#shutdown]); a thread left running would hold the old deployment's
/// classes after a redeploy. For the same reason webhooks go out on
/// `HttpURLConnection`, which keeps no thread of its own.
///
/// **SMTP from the settings, not a WebLogic mail session.** A session looked
/// up from JNDI comes from the server's class loader and the message classes
/// from the application's; the cast between them fails.
final class Notifier {

	private static final Logger logger = Logger.getLogger(Notifier.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static volatile Supplier<DashboardSettings> settings;

	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors
			.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "blade-dashboard-notifier");
				t.setDaemon(true);
				return t;
			});

	private Notifier() {
	}

	/// Stop the worker; called when the dashboard stops.
	static void shutdown() {
		WORKER.shutdownNow();
	}

	/// One change: `raised` true when trouble starts, false when it ends.
	static void notify(String kind, boolean raised, String subject, String server, String summary) {
		Supplier<DashboardSettings> s = settings;
		DashboardSettings current = s == null ? null : s.get();
		NotifySettings n = current == null ? null : current.getNotify();
		if (n == null || !n.getKinds().contains(kind) || (n.getWebhooks().isEmpty() && n.getEmailTo().isEmpty())) {
			return;
		}
		ObjectNode notice = notice(kind, raised, subject, server, summary, SettingsManager.getDomainName(),
				n.getBoardUrl(), System.currentTimeMillis());
		WORKER.execute(() -> deliver(n, notice));
	}

	/// The JSON a webhook receives. Package-private for the test.
	static ObjectNode notice(String kind, boolean raised, String subject, String server, String summary,
			String domain, String board, long at) {
		ObjectNode o = MAPPER.createObjectNode();
		o.put("state", raised ? "raised" : "cleared");
		o.put("kind", kind);
		o.put("subject", subject);
		o.put("server", server);
		o.put("summary", summary);
		o.put("at", java.time.Instant.ofEpochMilli(at).toString());
		o.put("domain", domain);
		if (board != null && !board.isEmpty()) {
			o.put("board", board);
		}
		o.put("text", line(o));
		return o;
	}

	/// One line a person reads: `RAISED trunk flowroute on engine0 (ashburn): 403 Forbidden`.
	static String line(ObjectNode o) {
		String summary = o.path("summary").asText("");
		return o.path("state").asText().toUpperCase(java.util.Locale.ROOT) + " " + o.path("kind").asText() + " "
				+ o.path("subject").asText() + (o.hasNonNull("server") ? " on " + o.path("server").asText() : "")
				+ (o.hasNonNull("domain") ? " (" + o.path("domain").asText() + ")" : "")
				+ (summary.isEmpty() ? "" : ": " + summary);
	}

	private static void deliver(NotifySettings n, ObjectNode notice) {
		for (String url : n.getWebhooks()) {
			try {
				java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
				c.setConnectTimeout(5000);
				c.setReadTimeout(10000);
				c.setRequestMethod("POST");
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				try (java.io.OutputStream out = c.getOutputStream()) {
					out.write(notice.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				}
				int status = c.getResponseCode();
				c.disconnect();
				if (status >= 300) {
					logger.warning("dashboard notice to " + url + " answered " + status);
				}
			} catch (Exception e) {
				logger.log(Level.WARNING, "dashboard notice to " + url + " failed: " + e, e);
			}
		}
		if (!n.getEmailTo().isEmpty() && n.getSmtpHost() != null && n.getEmailFrom() != null) {
			try {
				email(n, notice);
			} catch (Exception e) {
				logger.log(Level.WARNING, "dashboard notice email failed: " + e, e);
			}
		}
	}

	private static void email(NotifySettings n, ObjectNode notice) throws Exception {
		Properties p = new Properties();
		p.put("mail.smtp.host", n.getSmtpHost());
		p.put("mail.smtp.port", String.valueOf(n.getSmtpPort()));
		p.put("mail.smtp.starttls.enable", String.valueOf(n.isSmtpStartTls()));
		p.put("mail.smtp.connectiontimeout", "10000");
		p.put("mail.smtp.timeout", "10000");
		boolean auth = n.getSmtpUser() != null && !n.getSmtpUser().isEmpty();
		p.put("mail.smtp.auth", String.valueOf(auth));
		javax.mail.Session session = javax.mail.Session.getInstance(p, !auth ? null : new javax.mail.Authenticator() {
			@Override
			protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
				return new javax.mail.PasswordAuthentication(n.getSmtpUser(), n.getSmtpPassword());
			}
		});
		javax.mail.internet.MimeMessage m = new javax.mail.internet.MimeMessage(session);
		m.setFrom(new javax.mail.internet.InternetAddress(n.getEmailFrom()));
		for (String to : n.getEmailTo()) {
			m.addRecipient(javax.mail.Message.RecipientType.TO, new javax.mail.internet.InternetAddress(to));
		}
		m.setSubject("[BLADE] " + notice.path("text").asText(), "UTF-8");
		m.setText(notice.path("text").asText() + "\n\nAt " + notice.path("at").asText()
				+ (notice.hasNonNull("board") ? "\nThe board: " + notice.path("board").asText() : "") + "\n", "UTF-8");
		javax.mail.Transport.send(m);
	}
}
