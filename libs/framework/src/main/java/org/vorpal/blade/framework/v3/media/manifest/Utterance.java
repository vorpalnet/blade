package org.vorpal.blade.framework.v3.media.manifest;

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
}
