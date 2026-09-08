package org.vorpal.blade.framework.v3.media.manifest;

/// One word of an utterance placed on the conversation clock.
///
/// Derived from the recognizer's tokens rather than stored, because the tokens
/// are the evidence and a word boundary is an interpretation of them: a
/// transducer marks a word's first token with a leading space, and everything
/// up to the next such token, punctuation included, is that word. See
/// [Utterance#wordTimes].
public final class WordTime {

	private final String text;
	private final long startMillis;
	private final long endMillis;

	public WordTime(String text, long startMillis, long endMillis) {
		this.text = text;
		this.startMillis = startMillis;
		this.endMillis = endMillis;
	}

	/// The word as the recognizer spelt it, with any punctuation it attached.
	public String text() {
		return text;
	}

	/// Where the word begins, in milliseconds on the conversation clock.
	public long startMillis() {
		return startMillis;
	}

	/// Where the word ends on the same clock: its last token's start plus that
	/// token's duration, or its last token's start when the engine gave no
	/// durations.
	public long endMillis() {
		return endMillis;
	}

	@Override
	public String toString() {
		return text + "@" + startMillis + "-" + endMillis;
	}
}
