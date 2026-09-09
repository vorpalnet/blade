package org.vorpal.blade.applications.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


import org.vorpal.blade.framework.v3.media.manifest.ConversationManifest;
import org.vorpal.blade.framework.v3.media.manifest.Redactor;
import org.vorpal.blade.framework.v3.media.manifest.MediaGap;
import org.vorpal.blade.framework.v3.media.manifest.RecordingTrack;
import org.vorpal.blade.framework.v3.media.manifest.TranscriptRef;
import org.vorpal.blade.framework.v3.media.manifest.Utterance;

/// One conversation as the catalog stores it, derived from the record and
/// nothing else.
///
/// Everything here is a projection of the manifest and the stored utterances,
/// which is what makes the catalog a cache: any row can be produced again
/// from the archive, and a change to what is derived is a rebuild rather than
/// a migration. The facts a supervisor filters on come first: who called whom,
/// when, for how long, which department and queue, whether a card was taken,
/// how often it was held or transferred. Then the redacted text of every
/// utterance, for a search by words. The verbatim text is never stored; the
/// protected values found in it are stored as keyed hashes, so an exact
/// match can be asked for and nothing can be read out.
public final class CatalogRecord {

	/// The catalog's own schema revision, stamped on every row so a rebuild
	/// can find rows an older indexer wrote.
	public static final int VERSION = 1;

	public final String conversation;
	public final String call;
	public final String epochUtc;
	public final long durationMillis;
	public final boolean complete;
	public final String incompleteReason;
	public final String from;
	public final String to;
	public final Map<String, String> attributes;
	public final int utterances;
	public final int holds;
	public final int moves;
	/// Each redaction kind found, with how many times: `card=1,phone=2`.
	public final Map<String, Integer> kinds;
	public final List<Line> lines;
	public final List<Protected> protectedValues;
	public final String node;

	/// One utterance as searched: the redacted rendition and the kinds in it.
	public static final class Line {
		public final String transcript;
		public final int sequence;
		public final String party;
		public final long startMillis;
		public final long endMillis;
		public final String text;
		public final String kinds;

		Line(String transcript, int sequence, String party, long startMillis, long endMillis, String text, String kinds) {
			this.transcript = transcript;
			this.sequence = sequence;
			this.party = party;
			this.startMillis = startMillis;
			this.endMillis = endMillis;
			this.text = text;
			this.kinds = kinds;
		}
	}

	/// A protected value's kind and keyed hash. The value itself is not here.
	public static final class Protected {
		public final String kind;
		public final String digest;

		Protected(String kind, String digest) {
			this.kind = kind;
			this.digest = digest;
		}
	}

	private CatalogRecord(ConversationManifest m, Map<String, List<Utterance>> byTranscript, String key) {
		this.conversation = m.getConversation();
		this.call = m.getCall();
		this.epochUtc = m.getEpochUtc();
		this.durationMillis = (m.getDurationMillis() == null) ? 0L : m.getDurationMillis();
		this.complete = m.isComplete();
		this.incompleteReason = m.getIncompleteReason();
		Map<String, String> attrs = new TreeMap<>();
		if (m.getAttributes() != null) {
			attrs.putAll(m.getAttributes());
		}
		this.attributes = attrs;
		this.from = attrs.get("from");
		this.to = attrs.get("to");
		this.node = m.getFinalizedBy();

		int held = 0;
		int moved = 0;
		for (RecordingTrack track : m.getTracks()) {
			for (MediaGap gap : track.getGaps()) {
				if (gap.getReason() == MediaGap.Reason.HOLD) {
					held++;
				} else if (gap.getReason() == MediaGap.Reason.MOVED) {
					moved++;
				}
			}
		}
		this.holds = held;
		this.moves = moved;

		Map<String, Integer> found = new TreeMap<>();
		List<Line> text = new ArrayList<>();
		List<Protected> hashes = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		int count = 0;
		for (TranscriptRef ref : m.getTranscripts()) {
			List<Utterance> list = byTranscript.get(ref.getId());
			if (list == null) {
				continue;
			}
			boolean redacted = ref.getRedaction() == TranscriptRef.Redaction.REDACTED;
			for (Utterance u : list) {
				count++;
				// What is searchable is what a reader without phi:unredact sees.
				// A transcript the recorder never redacted has nothing withheld.
				String line = (redacted && u.getRedacted() != null) ? u.getRedacted()
						: (redacted ? "" : u.getText());
				Set<String> lineKinds = new LinkedHashSet<>();
				if (u.getRedactions() != null) {
					for (Utterance.Redaction r : u.getRedactions()) {
						found.merge(r.getKind(), 1, Integer::sum);
						lineKinds.add(r.getKind());
						if (key != null && !key.isEmpty() && u.getText() != null && r.getTo() <= u.getText().length()) {
							String digest = hmac(key, u.getText().substring(r.getFrom(), r.getTo()));
							if (seen.add(r.getKind() + ":" + digest)) {
								hashes.add(new Protected(r.getKind(), digest));
							}
						}
					}
				}
				text.add(new Line(ref.getId(), u.getSequence(), u.getParty(), u.getStartMillis(), u.getEndMillis(),
						line == null ? "" : line, String.join(",", lineKinds)));
			}
		}
		this.utterances = count;
		this.kinds = found;
		this.lines = text;
		this.protectedValues = hashes;
	}

	/// The record for a committed conversation. `key` is the protected-value
	/// key, or null or empty to store no hashes.
	public static CatalogRecord of(ConversationManifest manifest, Map<String, List<Utterance>> utterances, String key) {
		return new CatalogRecord(manifest, utterances == null ? new LinkedHashMap<>() : utterances, key);
	}

	/// See [Redactor#normalise].
	public static String normalise(String value) {
		return Redactor.normalise(value);
	}

	/// See [Redactor#digest]: the catalog stores this, never the value.
	public static String hmac(String key, String value) {
		return Redactor.digest(key, value);
	}

	/// `kind=count,kind=count`, the kinds column.
	public String kindsColumn() {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, Integer> e : kinds.entrySet()) {
			if (out.length() > 0) {
				out.append(',');
			}
			out.append(e.getKey()).append('=').append(e.getValue());
		}
		return out.toString();
	}
}
