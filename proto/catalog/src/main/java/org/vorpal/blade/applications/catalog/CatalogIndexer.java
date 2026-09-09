package org.vorpal.blade.applications.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventSubscriber;
import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.ManifestArchive;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptArchive;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// Turns a conversation-closed event into catalog rows.
///
/// The event is a pointer. Everything written comes from the archive: the
/// manifest and the stored utterances are read back, [CatalogRecord] derives
/// the row values, and the rows replace whatever the catalog held for that
/// conversation. Replacing rather than inserting makes a redelivered event, a
/// re-run after a schema change, and a rebuild from the archive the same
/// operation.
///
/// A conversation that cannot be read from the archive is skipped and said
/// so; the batch goes on. A database that cannot be reached fails the batch,
/// which the durable subscription redelivers, so a database outage delays the
/// catalog and loses nothing.
public class CatalogIndexer implements EventSubscriber.Handler {

	private static final Logger LOG = Logger.getLogger(CatalogIndexer.class.getName());

	private volatile boolean schemaReady;
	private volatile boolean fullText;

	@Override
	public void handle(List<CloudEvent> batch) throws Exception {
		CatalogSettings settings = CatalogSubscription.settings();
		if (settings == null) {
			throw new IllegalStateException("catalog has no settings");
		}
		DataSource ds = (DataSource) new InitialContext().lookup(settings.getDataSource());
		try (Connection c = ds.getConnection()) {
			c.setAutoCommit(false);
			if (!schemaReady) {
				fullText = CatalogSchema.ensure(c, settings.isFullText());
				c.commit();
				schemaReady = true;
			}
			for (CloudEvent event : batch) {
				String conversation = event.getData() == null ? null : event.getData().path("conversation").asText(null);
				if (conversation == null || conversation.isEmpty()) {
					LOG.warning("catalog: an event without a conversation was skipped: " + event.getId());
					continue;
				}
				try {
					index(c, conversation, settings.getProtectedValueKey());
					c.commit();
				} catch (java.io.IOException unreadable) {
					c.rollback();
					LOG.log(Level.WARNING, "catalog: " + conversation + " could not be read from the archive and was skipped",
							unreadable);
				}
			}
		}
	}

	/// Whether the full-text index exists, as seen at schema time.
	public boolean fullText() {
		return fullText;
	}

	/// Index one conversation from the archive into the catalog.
	static void index(Connection c, String conversation, String key) throws SQLException, java.io.IOException {
		ManifestArchive manifests = ManifestArchive.installed();
		TranscriptArchive transcripts = TranscriptArchive.installed();
		if (manifests == null) {
			throw new IllegalStateException("catalog has no ManifestArchive on the classpath");
		}
		ConversationManifest manifest = manifests.get(conversation);
		if (manifest == null) {
			throw new java.io.IOException("no manifest for " + conversation);
		}
		Map<String, List<Utterance>> utterances = new LinkedHashMap<>();
		if (transcripts != null) {
			for (TranscriptRef ref : manifest.getTranscripts()) {
				if (ref.getObject() != null && ref.getObject().endsWith("/")) {
					utterances.put(ref.getId(), transcripts.read(conversation, ref.getId()));
				}
			}
		}
		write(c, CatalogRecord.of(manifest, utterances, key));
	}

	static void write(Connection c, CatalogRecord r) throws SQLException {
		for (String table : new String[] { "BLADE_LABEL", "BLADE_PROTECTED", "BLADE_UTTERANCE", "BLADE_CONVERSATION_ATTR",
				"BLADE_CONVERSATION" }) {
			try (PreparedStatement d = c.prepareStatement("DELETE FROM " + table + " WHERE CONVERSATION = ?")) {
				d.setString(1, r.conversation);
				d.executeUpdate();
			}
		}
		try (PreparedStatement p = c.prepareStatement("INSERT INTO BLADE_CONVERSATION (CONVERSATION, CALL_ID, EPOCH_UTC, "
				+ "DURATION_MS, COMPLETE, INCOMPLETE_REASON, FROM_NUMBER, TO_NUMBER, UTTERANCES, HOLDS, MOVES, KINDS, NODE, "
				+ "INDEXED_UTC, INDEX_VERSION) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
			p.setString(1, r.conversation);
			p.setString(2, r.call);
			p.setTimestamp(3, r.epochUtc == null ? null : Timestamp.from(Instant.parse(r.epochUtc)));
			p.setLong(4, r.durationMillis);
			p.setInt(5, r.complete ? 1 : 0);
			p.setString(6, clip(r.incompleteReason, 400));
			p.setString(7, clip(r.from, 64));
			p.setString(8, clip(r.to, 64));
			p.setInt(9, r.utterances);
			p.setInt(10, r.holds);
			p.setInt(11, r.moves);
			p.setString(12, clip(r.kindsColumn(), 400));
			p.setString(13, clip(r.node, 64));
			p.setTimestamp(14, Timestamp.from(Instant.now()));
			p.setInt(15, CatalogRecord.VERSION);
			p.executeUpdate();
		}
		try (PreparedStatement p = c.prepareStatement(
				"INSERT INTO BLADE_CONVERSATION_ATTR (CONVERSATION, NAME, VALUE) VALUES (?,?,?)")) {
			for (Map.Entry<String, String> e : r.attributes.entrySet()) {
				if (e.getKey() == null || e.getKey().length() > 64 || e.getValue() == null) {
					continue;
				}
				p.setString(1, r.conversation);
				p.setString(2, e.getKey());
				p.setString(3, clip(e.getValue(), 400));
				p.addBatch();
			}
			p.executeBatch();
		}
		try (PreparedStatement p = c.prepareStatement("INSERT INTO BLADE_UTTERANCE (CONVERSATION, TRANSCRIPT, SEQUENCE, "
				+ "PARTY, START_MS, END_MS, TEXT, KINDS) VALUES (?,?,?,?,?,?,?,?)")) {
			for (CatalogRecord.Line line : r.lines) {
				p.setString(1, r.conversation);
				p.setString(2, clip(line.transcript, 32));
				p.setInt(3, line.sequence);
				p.setString(4, clip(line.party, 64));
				p.setLong(5, line.startMillis);
				p.setLong(6, line.endMillis);
				p.setString(7, clip(line.text, 4000));
				p.setString(8, clip(line.kinds, 200));
				p.addBatch();
			}
			p.executeBatch();
		}
		try (PreparedStatement p = c.prepareStatement(
				"INSERT INTO BLADE_PROTECTED (CONVERSATION, KIND, DIGEST) VALUES (?,?,?)")) {
			for (CatalogRecord.Protected v : r.protectedValues) {
				p.setString(1, r.conversation);
				p.setString(2, clip(v.kind, 64));
				p.setString(3, v.digest);
				p.addBatch();
			}
			p.executeBatch();
		}
	}

	private static String clip(String s, int max) {
		if (s == null) {
			return null;
		}
		return (s.length() <= max) ? s : s.substring(0, max);
	}
}
