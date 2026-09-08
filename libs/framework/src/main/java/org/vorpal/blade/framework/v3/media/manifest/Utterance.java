package org.vorpal.blade.framework.v3.media.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;

/// One thing one party said, as a transcript stores it.
///
/// A transcript is written while the call runs, one object per utterance,
/// because the archive refuses to rewrite an object and a transcript that grows
/// cannot be one file. Each object is therefore complete on its own: who spoke,
/// when, what, and what produced the text. Reading a transcript back is reading
/// the objects under [TranscriptRef#getObject] in sequence order.
///
/// ## The bounds are conversation offsets
///
/// `startMillis` and `endMillis` are on the same clock as every track in the
/// manifest, so a line of text and the audio behind it are the same number.
/// They are where the speech was, not where the recognizer ran: a transcript
/// of record must place the words at the moment they were said, and the decode
/// that produced them may have finished seconds later.
///
/// ## Provenance is per utterance
///
/// The engine and model travel on every utterance rather than once on the
/// transcript, because a recognizer under load may not decode every utterance
/// the same way. A line that fell back to a lesser decoder has to be visible as
/// such, and a reviewer challenging one line needs to know what produced that
/// line and not the average.
///
/// ## Sequence is the application's
///
/// The number is assigned by whoever stores the utterance, in arrival order,
/// and it is the object's name in the archive. Bounds give the true order when
/// two parties overlap; the sequence gives a stable name and a way to see that
/// an utterance is missing.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Utterance {

	private int sequence;
	private String party;
	private long startMillis;
	private long endMillis;
	private String text;
	private String engine;
	private String model;
	private JsonNode words;
	private String heard;
	private List<Correction> corrections;

	public Utterance() {
	}

	public Utterance(String party, long startMillis, long endMillis, String text) {
		this.party = party;
		this.startMillis = startMillis;
		this.endMillis = endMillis;
		this.text = text;
	}

	@JsonPropertyDescription("Position within the transcript, from 1, in arrival order. Also the object's name.")
	public int getSequence() {
		return sequence;
	}

	public void setSequence(int sequence) {
		this.sequence = sequence;
	}

	@JsonPropertyDescription("Who spoke: a party label such as caller or callee, or a track id.")
	public String getParty() {
		return party;
	}

	public void setParty(String party) {
		this.party = party;
	}

	@JsonPropertyDescription("Where the speech began, in milliseconds on the conversation clock.")
	public long getStartMillis() {
		return startMillis;
	}

	public void setStartMillis(long startMillis) {
		this.startMillis = startMillis;
	}

	@JsonPropertyDescription("Where the speech ended, on the same clock.")
	public long getEndMillis() {
		return endMillis;
	}

	public void setEndMillis(long endMillis) {
		this.endMillis = endMillis;
	}

	@JsonPropertyDescription("What was said, as the recognizer produced it.")
	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	@JsonPropertyDescription("Recognizer that produced this text, for example whisper.")
	public String getEngine() {
		return engine;
	}

	public void setEngine(String engine) {
		this.engine = engine;
	}

	@JsonPropertyDescription("Model that produced it, so a line can be compared against a later re-run.")
	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	/// What the recognizer wrote before any correction, present only when
	/// [#getText] differs from it. The record keeps what was heard; the
	/// correction is an interpretation of it against what the call was known
	/// to involve, and a reviewer is entitled to both.
	@JsonPropertyDescription("The recognizer's text before correction. Absent when nothing was corrected.")
	public String getHeard() {
		return heard;
	}

	public void setHeard(String heard) {
		this.heard = heard;
	}

	@JsonPropertyDescription("Each span replaced in the text, from what was heard to the expected phrase.")
	public List<Correction> getCorrections() {
		return corrections;
	}

	public void setCorrections(List<Correction> corrections) {
		this.corrections = corrections;
	}

	/// One span of text replaced by an expected phrase.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class Correction {
		private String from;
		private String to;

		public Correction() {
		}

		public Correction(String from, String to) {
			this.from = from;
			this.to = to;
		}

		@JsonPropertyDescription("The words the recognizer wrote.")
		public String getFrom() {
			return from;
		}

		public void setFrom(String from) {
			this.from = from;
		}

		@JsonPropertyDescription("The expected phrase they were replaced with.")
		public String getTo() {
			return to;
		}

		public void setTo(String to) {
			this.to = to;
		}
	}

	/// The recognizer's own structured result, when it produced one: tokens
	/// and, where the model emits them, their offsets within the utterance.
	/// Passed through rather than reshaped, because the shape is the engine's.
	/// A transducer gives `tokens`, `timestamps` (seconds from the start of the
	/// utterance) and `durations`, with a word's first token carrying a leading
	/// space; a word's time on the conversation clock is therefore
	/// `startMillis` plus a thousand times its first token's timestamp.
	@JsonPropertyDescription("The recognizer's per-token result, as it produced it. Absent when the engine gave none.")
	public JsonNode getWords() {
		return words;
	}

	public void setWords(JsonNode words) {
		this.words = words;
	}

	/// How long the speech lasted.
	public long durationMillis() {
		return Math.max(endMillis - startMillis, 0L);
	}

	/// The words of this utterance on the conversation clock, read out of
	/// [#getWords]; empty when the engine gave no timing.
	///
	/// A word begins at a token carrying a leading space, or at the first
	/// token, and takes every following token up to the next such one, so
	/// punctuation stays with the word it follows. Its end is its last token's
	/// start plus that token's duration when durations were given. This is
	/// what lets a reviewer jump from a word to the audio behind it.
	@JsonIgnore
	public List<WordTime> wordTimes() {
		if (words == null) {
			return Collections.emptyList();
		}
		JsonNode tokens = words.path("tokens");
		JsonNode stamps = words.path("timestamps");
		JsonNode durations = words.path("durations");
		if (!tokens.isArray() || !stamps.isArray() || tokens.size() == 0 || stamps.size() != tokens.size()) {
			return Collections.emptyList();
		}
		List<WordTime> out = new ArrayList<>();
		StringBuilder text = null;
		long start = 0;
		long end = 0;
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i).asText();
			long at = startMillis + Math.round(stamps.get(i).asDouble() * 1000.0);
			long duration = (durations.isArray() && durations.size() > i)
					? Math.round(durations.get(i).asDouble() * 1000.0) : 0L;
			boolean wordStart = token.startsWith(" ") || token.startsWith("\u2581");
			if (wordStart && text != null && text.length() > 0) {
				out.add(new WordTime(text.toString(), start, end));
				text = null;
			}
			if (text == null) {
				text = new StringBuilder();
				start = at;
				end = at;
			}
			text.append(token.startsWith("\u2581") ? token.substring(1).strip() : token.strip());
			end = Math.max(end, at + duration);
		}
		if (text != null && text.length() > 0) {
			out.add(new WordTime(text.toString(), start, end));
		}
		return out;
	}
}
