package org.vorpal.blade.applications.catalog;

import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.events.SubscriptionRegistrar;
import org.vorpal.blade.framework.v3.media.manifest.ManifestArchive;

/// The catalog's durable subscription to the conversation-closed event.
///
/// Durable, so a catalog that was down while calls closed catches up when it
/// comes back, and the batch is small so a redelivery repeats little. The
/// handler replaces rows rather than inserting them, so a repeat is harmless.
///
/// The application refuses to start without an archive reader on the
/// classpath, the same way the audit sink refuses without a sink: a
/// subscription that consumed events and could not read what they point at
/// would drain the subscription and index nothing.
public class CatalogSubscription implements ServletContextListener {

	static final String SUBSCRIPTION = "blade-catalog";
	private static final int BATCH_SIZE = 8;

	static List<String> types() {
		return Arrays.asList(BladeEventTypes.CONVERSATION_CLOSED);
	}

	private static SettingsManager<CatalogSettings> settings;

	static CatalogSettings settings() {
		return (settings == null) ? null : settings.getCurrent();
	}

	private final CatalogIndexer handler = new CatalogIndexer();
	private SubscriptionRegistrar registrar;

	@Override
	public void contextInitialized(ServletContextEvent event) {
		try {
			settings = new SettingsManager<>(event, CatalogSettings.class, new CatalogSettingsSample());
		} catch (Exception e) {
			throw new IllegalStateException("blade-catalog could not load its settings", e);
		}
		if (ManifestArchive.installed() == null) {
			throw new IllegalStateException("blade-catalog will not start without a ManifestArchive on the classpath: "
					+ "conversation events would be consumed and nothing indexed");
		}
		SubscriptionRegistrar.meter(event.getServletContext(), SUBSCRIPTION);
		registrar = SubscriptionRegistrar.start(SUBSCRIPTION, CatalogSubscription::types, true, handler, BATCH_SIZE,
				EventSubscriber.DEFAULT_BATCH_MILLIS);
		CatalogSettings current = settings();
		if (current != null && current.getRebuildDays() > 0) {
			rebuild(current.getRebuildDays());
		}
	}

	/// Re-index the last `days` days from the archive, in the background,
	/// oldest first. Every conversation the archive lists for a day is read
	/// back and its rows replaced, the same path an event takes, so the
	/// rebuild and the live feed never disagree about a row's shape.
	static void rebuild(int days) {
		Thread worker = new Thread(() -> {
			java.util.logging.Logger log = java.util.logging.Logger.getLogger(CatalogSubscription.class.getName());
			org.vorpal.blade.framework.v3.media.RecordingArchive archive = org.vorpal.blade.framework.v3.media.RecordingArchive
					.installed();
			CatalogSettings cfg = settings();
			if (archive == null || cfg == null) {
				log.warning("catalog: no RecordingArchive to rebuild from");
				return;
			}
			int indexed = 0;
			int failed = 0;
			try {
				javax.sql.DataSource ds = (javax.sql.DataSource) new javax.naming.InitialContext().lookup(cfg.getDataSource());
				try (java.sql.Connection c = ds.getConnection()) {
					c.setAutoCommit(false);
					CatalogSchema.ensure(c, cfg.isFullText());
					c.commit();
					java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
					for (int back = days - 1; back >= 0; back--) {
						String day = today.minusDays(back).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
						for (org.vorpal.blade.framework.v3.media.RecordingArchive.RecordingSummary r : archive.list(day)) {
							String conversation = conversationOf(r.id());
							try {
								CatalogIndexer.index(c, conversation, cfg.getProtectedValueKey());
								c.commit();
								indexed++;
							} catch (Exception e) {
								c.rollback();
								failed++;
								log.fine("catalog: rebuild skipped " + conversation + ": " + e);
							}
						}
					}
				}
			} catch (Exception e) {
				log.log(java.util.logging.Level.WARNING, "catalog: rebuild stopped: " + e, e);
			}
			log.info("catalog: rebuilt " + indexed + " conversation(s) from the last " + days + " day(s)"
					+ (failed > 0 ? ", " + failed + " could not be read" : ""));
		}, "blade-catalog-rebuild");
		worker.setDaemon(true);
		worker.start();
	}

	/// The conversation id a recording id refers to: the same identifier with
	/// the timestamp half in lower case, as the manifest names it.
	static String conversationOf(String recordingId) {
		int dot = recordingId.indexOf('.');
		if (dot < 0) {
			return recordingId.toUpperCase(java.util.Locale.ROOT);
		}
		return recordingId.substring(0, dot).toUpperCase(java.util.Locale.ROOT) + "."
				+ recordingId.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {
		if (registrar != null) {
			registrar.stop();
		}
		if (settings != null) {
			try {
				settings.unregister();
			} catch (Exception ignore) {
				// nothing to do at shutdown
			}
		}
	}
}
