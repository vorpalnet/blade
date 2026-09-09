package org.vorpal.blade.framework.v3.media.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Finds protected values in an utterance's text and writes the redacted
/// rendition beside it.
///
/// ## What it looks for
///
/// Values a caller reads out and a transcript must not hand to everyone who
/// may read the words: card numbers, social security numbers, phone numbers,
/// account and member identifiers, numeric dates. Each is a named kind with a
/// regular expression, matched against the text the recognizer produced. The
/// recognizers in use write numbers as digits, so "four one one one" arrives
/// as `4111`, and a value is matched the way it was written.
///
/// A card number is the one kind with a test beyond its shape: a run of
/// thirteen to nineteen digits is a card only if its check digit agrees,
/// which keeps a long account number from being called a card and, more to
/// the point, keeps a card from being called an account number and shown.
///
/// ## Both renditions are stored
///
/// `apply` leaves [Utterance#getText] as it was and fills
/// [Utterance#getRedacted] and [Utterance#getRedactions]. The record keeps
/// what was said; which rendition a reader is shown is decided when they read
/// it, against `phi:unredact`. Redacting in storage would make the one reader
/// entitled to the value unable to get it, and would make the audio, which
/// still carries the value, the only place it survives.
///
/// Each span also carries the time the words behind it were spoken, from the
/// recognizer's word timing when it gave any. That is what a player needs to
/// mute the same span in the audio, which is the half of redaction this class
/// does not do.
///
/// ## Configuration
///
/// [#of] takes kind to expression. An empty map means the built-in set; a
/// kind named like a built-in one replaces it, and a kind mapped to an empty
/// expression removes it, so a deployment whose member identifiers have a
/// shape of their own adds one line and keeps the rest. The deployment's
/// kinds are tried before the built-in ones, in the order given, and a
/// character belongs to the first span that claims it.
public final class Redactor {

	/// The kinds found by default, in the order they are tried.
	static final Map<String, String> DEFAULTS;
	static {
		Map<String, String> d = new LinkedHashMap<>();
		// 13 to 19 digits, spaces or dashes between; the check digit decides.
		d.put("card", "\\b\\d(?:[ -]?\\d){12,18}\\b");
		d.put("ssn", "\\b\\d{3}[ -]?\\d{2}[ -]?\\d{4}\\b");
		d.put("phone", "(?<![\\w(])(?:\\+?1[ -]?)?\\(?\\d{3}\\)?[ -]?\\d{3}[ -]?\\d{4}\\b");
		// An identifier: letters then digits, or six or more digits with or
		// without separators, the way an account or member number is read out.
		d.put("number", "\\b(?:[A-Z]{1,4}[ -]?\\d{4,}|\\d(?:[ -]?\\d){5,})\\b");
		d.put("date", "\\b(?:\\d{1,2}/\\d{1,2}/\\d{2,4}|(?:January|February|March|April|May|June|July|August|September|October|November|December)"
				+ " \\d{1,2}(?:st|nd|rd|th)?,? \\d{4})\\b");
		// A street address as it is read out: number, name, a street word.
		d.put("address", "\\b\\d{1,6} (?:[A-Z][a-z]+ ){1,3}(?:Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Lane|Ln|Boulevard|Blvd|Court|Ct|Way|Place|Pl|Terrace|Circle|Highway|Hwy)\\b");
		DEFAULTS = Collections.unmodifiableMap(d);
	}

	private static final Redactor NONE = new Redactor(Collections.<String, String>emptyMap(), false);

	private static final class Rule {
		final String kind;
		final Pattern pattern;

		Rule(String kind, String regex) {
			this.kind = kind;
			this.pattern = Pattern.compile(regex);
		}
	}

	private final List<Rule> rules = new ArrayList<>();
	private final List<Rule> phrases = new ArrayList<>();

	private Redactor(Map<String, String> kinds, boolean withDefaults) {
		// The deployment's kinds first: a member identifier with a shape of its
		// own must be found as one before the general number rule claims it.
		Map<String, String> merged = new LinkedHashMap<>();
		if (kinds != null) {
			merged.putAll(kinds);
		}
		if (withDefaults) {
			for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
				merged.putIfAbsent(e.getKey(), e.getValue());
			}
		}
		for (Map.Entry<String, String> e : merged.entrySet()) {
			String regex = (e.getValue() == null) ? "" : e.getValue().trim();
			if (!regex.isEmpty()) {
				rules.add(new Rule(e.getKey().trim(), regex));
			}
		}
	}

	/// The built-in kinds, with `kinds` layered over them: a matching name
	/// replaces the built-in expression, an empty expression removes it.
	public static Redactor of(Map<String, String> kinds) {
		return new Redactor(kinds, true);
	}

	/// The built-in set alone.
	public static Redactor defaults() {
		return new Redactor(null, true);
	}

	/// A redactor that finds nothing, for a deployment that has turned
	/// redaction off.
	public static Redactor none() {
		return NONE;
	}

	public boolean isEmpty() {
		return rules.isEmpty() && phrases.isEmpty();
	}

	/// The same redactor, also finding the given values verbatim, each under
	/// its own kind. This is how the things a call is known to involve become
	/// protected spans: the caller's name a Selector looked up, the member
	/// identifier the session carries. They have no shape a pattern finds, and
	/// they need none, because the recorder knows them before the first word.
	/// The match is case-insensitive and tolerant of punctuation the
	/// recognizer adds around a value.
	public Redactor withPhrases(Map<String, String> kindToValue) {
		if (kindToValue == null || kindToValue.isEmpty()) {
			return this;
		}
		Redactor r = new Redactor(this);
		for (Map.Entry<String, String> e : kindToValue.entrySet()) {
			String value = (e.getValue() == null) ? "" : e.getValue().trim();
			if (value.isEmpty() || e.getKey() == null || e.getKey().trim().isEmpty()) {
				continue;
			}
			// Each word of the value, in order, with any punctuation and
			// spacing the recognizer put between them.
			StringBuilder regex = new StringBuilder("(?i)(?<![\\p{L}\\p{N}])");
			String[] words = value.split("\\s+");
			for (int i = 0; i < words.length; i++) {
				if (i > 0) {
					regex.append("[\\s\\p{Punct}]+");
				}
				regex.append(Pattern.quote(words[i]));
			}
			regex.append("(?![\\p{L}\\p{N}])");
			r.phrases.add(new Rule(e.getKey().trim(), regex.toString()));
		}
		// longest first, so a full name is one span rather than two
		r.phrases.sort((a, b) -> Integer.compare(b.pattern.pattern().length(), a.pattern.pattern().length()));
		return r;
	}

	private Redactor(Redactor base) {
		this.rules.addAll(base.rules);
		this.phrases.addAll(base.phrases);
	}

	/// The protected spans in `text`, in text order, none overlapping.
	public List<Utterance.Redaction> find(String text) {
		List<Utterance.Redaction> found = new ArrayList<>();
		if (text == null || text.isEmpty() || isEmpty()) {
			return found;
		}
		List<Rule> all = new ArrayList<>(phrases);
		all.addAll(rules);
		for (Rule rule : all) {
			Matcher m = rule.pattern.matcher(text);
			while (m.find()) {
				if ("card".equals(rule.kind) && !luhn(m.group())) {
					continue;
				}
				if (!overlaps(found, m.start(), m.end())) {
					found.add(new Utterance.Redaction(rule.kind, m.start(), m.end()));
				}
			}
		}
		found.sort((a, b) -> Integer.compare(a.getFrom(), b.getFrom()));
		return found;
	}

	/// `text` with each span replaced by its kind in brackets.
	public static String mask(String text, List<Utterance.Redaction> spans) {
		if (spans == null || spans.isEmpty()) {
			return text;
		}
		StringBuilder out = new StringBuilder();
		int at = 0;
		for (Utterance.Redaction r : spans) {
			out.append(text, at, r.getFrom()).append('[').append(r.getKind()).append(']');
			at = r.getTo();
		}
		out.append(text.substring(at));
		return out.toString();
	}

	/// Redact the utterance in place: its text is kept, the redacted rendition
	/// and the spans are set, and each span is placed on the conversation clock
	/// from the words it covers. Returns whether anything was found.
	public boolean apply(Utterance utterance) {
		if (utterance == null || utterance.getText() == null || isEmpty()) {
			return false;
		}
		List<Utterance.Redaction> spans = find(utterance.getText());
		utterance.setRedacted(mask(utterance.getText(), spans));
		utterance.setRedactions(spans);
		if (spans.isEmpty()) {
			return false;
		}
		time(utterance.getText(), spans, utterance.wordTimes());
		return true;
	}

	/// Give each span the time of the words it covers. Words are located in
	/// the text in order, each from where the last one ended, so a word that
	/// occurs twice is matched to its own occurrence.
	static void time(String text, List<Utterance.Redaction> spans, List<WordTime> words) {
		if (words == null || words.isEmpty()) {
			return;
		}
		int cursor = 0;
		for (WordTime w : words) {
			String token = w.text();
			if (token == null || token.isEmpty()) {
				continue;
			}
			int at = text.indexOf(token, cursor);
			if (at < 0) {
				continue;
			}
			int end = at + token.length();
			cursor = end;
			for (Utterance.Redaction r : spans) {
				if (at < r.getTo() && end > r.getFrom()) {
					r.setStartMillis(r.getStartMillis() == null ? w.startMillis()
							: Math.min(r.getStartMillis(), w.startMillis()));
					r.setEndMillis(r.getEndMillis() == null ? w.endMillis() : Math.max(r.getEndMillis(), w.endMillis()));
				}
			}
		}
	}

	private static boolean overlaps(List<Utterance.Redaction> spans, int from, int to) {
		for (Utterance.Redaction r : spans) {
			if (from < r.getTo() && to > r.getFrom()) {
				return true;
			}
		}
		return false;
	}

	/// The card check digit test, over the digits of `value`.
	static boolean luhn(String value) {
		int sum = 0;
		boolean twice = false;
		int digits = 0;
		for (int i = value.length() - 1; i >= 0; i--) {
			char c = value.charAt(i);
			if (c < '0' || c > '9') {
				continue;
			}
			int d = c - '0';
			if (twice) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			sum += d;
			twice = !twice;
			digits++;
		}
		return digits >= 13 && sum % 10 == 0;
	}

	/// A protected value as it is hashed: letters and digits only, upper case,
	/// so "4111 1111 1111 1111", "4111-1111-1111-1111" and the same value typed
	/// without separators are one value.
	public static String normalise(String value) {
		StringBuilder out = new StringBuilder();
		for (char c : value.toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				out.append(Character.toUpperCase(c));
			}
		}
		return out.toString();
	}

	/// The keyed hash a catalog stores for a protected value instead of the
	/// value: HMAC-SHA256 of the normalised value under the deployment's key,
	/// as hex. The catalog and the review API compute it the same way, so an
	/// exact-match search for a member id works without the id being anywhere
	/// a copy of the catalog could give it up.
	public static String digest(String key, String value) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
					"HmacSHA256"));
			byte[] d = mac.doFinal(normalise(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : d) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}
}
