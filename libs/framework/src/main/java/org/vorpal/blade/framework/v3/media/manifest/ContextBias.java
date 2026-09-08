package org.vorpal.blade.framework.v3.media.manifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Corrects a decoded utterance toward the names and identifiers a call is
/// known to involve.
///
/// ## Why this exists beside the recognizer's own biasing
///
/// A recognizer can be leaned toward expected phrases, and the driver does
/// that where it can. It helps only when the decode already starts down the
/// right path: on narrowband audio the opening consonant of a name is the
/// thing most often lost, and a hint that begins with the wrong letter is
/// never entered. "Vorpal" came back as "Four pole" and "Porpal", "Okonkwo"
/// as "Akongwo" and "Aconquo", with the hint in place. Those are the same
/// sounds spelt differently, which is what a phonetic comparison is for.
///
/// ## What it will and will not change
///
/// A name is replaced when a span of the text sounds the same as the expected
/// name and is spelt close enough to it, and not when the span is made only of
/// common words: "for bill" sounds like "Vorpal" and is left alone. An
/// identifier is replaced when the text's letters and digits, spaces and
/// punctuation ignored, are within one character of it. A phrase already
/// present is not touched. Nothing is invented: a name the recognizer dropped
/// entirely stays dropped.
///
/// ## The record keeps what was heard
///
/// Every replacement is written down on the utterance, from and to, and the
/// recognizer's text is kept beside the corrected one. A reviewer sees the
/// correction as a correction, and can disagree with it.
public final class ContextBias {

	/// The most common words of spoken English, plus number words. A span made
	/// only of these is never rewritten into a name, however it sounds.
	private static final Set<String> COMMON = new HashSet<>(Arrays.asList(("the be to of and a in that have i it for not on with "
			+ "he as you do at this but his by from they we say her she or an will my one all would there their what so up "
			+ "out if about who get which go me when make can like time no just him know take people into year your good "
			+ "some could them see other than then now look only come its over think also back after use two how our work "
			+ "first well way even new want because any these give day most us is are was were been has had did am "
			+ "four pole poll bill fill call four for far more sure four pull full hole whole hold told sold bold cold old "
			+ "three five six seven eight nine ten zero oh hundred thousand yes yeah okay ok right please thanks thank hello hi "
			+ "car card cars phone number account member id name last week next this that here there").split(" ")));

	private final List<Hint> hints = new ArrayList<>();

	private ContextBias(Collection<String> phrases) {
		for (String phrase : phrases) {
			if (phrase == null || phrase.trim().isEmpty()) {
				continue;
			}
			Hint hint = new Hint(phrase.trim());
			if (!hint.alnum.isEmpty()) {
				hints.add(hint);
			}
		}
		// longest first, so "Yolanda Okonkwo" wins over "Yolanda"
		hints.sort((a, b) -> Integer.compare(b.alnum.length(), a.alnum.length()));
	}

	/// A bias toward `phrases`. Null or empty means no corrections ever.
	public static ContextBias of(Collection<String> phrases) {
		return new ContextBias(phrases == null ? Collections.<String>emptyList() : phrases);
	}

	public boolean isEmpty() {
		return hints.isEmpty();
	}

	/// Correct `text` and return it, or return `text` itself when nothing
	/// matched. `corrections`, when given, receives what was replaced.
	public String correct(String text, List<Utterance.Correction> corrections) {
		if (text == null || text.isEmpty() || hints.isEmpty()) {
			return text;
		}
		List<String> tokens = new ArrayList<>(Arrays.asList(text.trim().split("\\s+")));
		boolean[] fixed = new boolean[tokens.size()];
		boolean changed = false;
		for (Hint hint : hints) {
			// Every window the hint could stand for, then the closest one, so a
			// three-token exact match beats a two-token near miss.
			int bestAt = -1;
			int bestN = 0;
			int bestDistance = Integer.MAX_VALUE;
			int width = hint.identifier ? 4 : hint.words + 1;
			for (int n = Math.max(1, hint.identifier ? 1 : hint.words - 1); n <= width; n++) {
				for (int i = 0; i + n <= tokens.size(); i++) {
					if (anyFixed(fixed, i, n)) {
						continue;
					}
					List<String> window = tokens.subList(i, i + n);
					int d = hint.distanceIfMatch(window);
					if (d >= 0 && (d < bestDistance || (d == bestDistance && n > bestN))) {
						bestDistance = d;
						bestAt = i;
						bestN = n;
					}
				}
			}
			if (bestAt < 0) {
				continue;
			}
			List<String> window = tokens.subList(bestAt, bestAt + bestN);
			String from = String.join(" ", window);
			String replacement = hint.original + trailingPunctuation(window.get(bestN - 1));
			for (int k = 0; k < bestN; k++) {
				tokens.remove(bestAt);
				fixed = remove(fixed, bestAt);
			}
			tokens.add(bestAt, replacement);
			fixed = insert(fixed, bestAt);
			if (corrections != null) {
				corrections.add(new Utterance.Correction(from, hint.original));
			}
			changed = true;
		}
		return changed ? String.join(" ", tokens) : text;
	}

	/// Correct the utterance in place: its text is replaced, what was heard is
	/// kept on it, and each replacement is listed. Returns whether anything
	/// changed.
	public boolean apply(Utterance utterance) {
		if (utterance == null || utterance.getText() == null) {
			return false;
		}
		List<Utterance.Correction> made = new ArrayList<>();
		String corrected = correct(utterance.getText(), made);
		if (made.isEmpty()) {
			return false;
		}
		utterance.setHeard(utterance.getText());
		utterance.setText(corrected);
		utterance.setCorrections(made);
		return true;
	}

	// ------------------------------------------------------------ the hint

	private static final class Hint {
		final String original;
		final int words;
		final String alnum;
		final String key;
		final boolean identifier;

		Hint(String original) {
			this.original = original;
			this.words = original.split("\\s+").length;
			this.alnum = alnum(original);
			this.key = phonetic(alnum);
			this.identifier = alnum.chars().anyMatch(Character::isDigit);
		}

		/// The letter distance between the window and the hint when the window
		/// should become the hint, or -1 when it should not.
		int distanceIfMatch(List<String> window) {
			StringBuilder joined = new StringBuilder();
			boolean allCommon = true;
			for (String token : window) {
				String a = alnum(token);
				joined.append(a);
				if (!COMMON.contains(a)) {
					allCommon = false;
				}
			}
			String candidate = joined.toString();
			if (candidate.isEmpty()) {
				return -1;
			}
			if (candidate.equals(alnum)) {
				// The same letters and digits. A name in any casing is already
				// right; an identifier is right only in its exact form, so
				// "KR 742916" still becomes "KR742916".
				String surface = String.join(" ", window);
				surface = surface.substring(0, surface.length() - trailingPunctuation(surface).length());
				return identifier && !surface.equals(original) ? 0 : -1;
			}
			if (!identifier && candidate.contains(alnum)) {
				return -1; // the name is already in there; a wider window is not a miss
			}
			int d = distance(candidate, alnum);
			if (identifier) {
				// letters and digits only, within one character; short ones exact
				return (alnum.length() >= 6 && d <= 1) ? d : -1;
			}
			if (alnum.length() < 4) {
				return -1;
			}
			double ratio = d / (double) Math.max(candidate.length(), alnum.length());
			if (phonetic(candidate).equals(key)) {
				return (ratio <= 0.5 || (ratio <= 0.65 && !allCommon)) ? d : -1;
			}
			// A long name with an extra or missing syllable fails the phonetic key
			// and still cannot be anything else: "Yolanda of Conquer" for
			// "Yolanda Okonkwo". Letters alone decide it, and only for names long
			// enough that chance cannot.
			return (alnum.length() >= 10 && ratio <= 0.4 && !allCommon) ? d : -1;
		}
	}

	// ------------------------------------------------------------ helpers

	static String alnum(String s) {
		StringBuilder out = new StringBuilder();
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				out.append(c);
			}
		}
		return out.toString();
	}

	/// A whole-string phonetic key in the Soundex families, first letter
	/// included and vowels dropped, so a name and its misspelling meet:
	/// vorpal, forbel, porpal and "four pole" all key to 1614.
	static String phonetic(String alnum) {
		StringBuilder out = new StringBuilder();
		char last = 0;
		for (char c : alnum.toCharArray()) {
			char code;
			switch (c) {
			case 'b': case 'f': case 'p': case 'v':
				code = '1';
				break;
			case 'c': case 'g': case 'j': case 'k': case 'q': case 's': case 'x': case 'z':
				code = '2';
				break;
			case 'd': case 't':
				code = '3';
				break;
			case 'l':
				code = '4';
				break;
			case 'm': case 'n':
				code = '5';
				break;
			case 'r':
				code = '6';
				break;
			default:
				code = Character.isDigit(c) ? c : 0;
			}
			if (code != 0 && code != last) {
				out.append(code);
			}
			if (code != 0) {
				last = code;
			}
		}
		return out.toString();
	}

	static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}

	private static String trailingPunctuation(String token) {
		int end = token.length();
		while (end > 0 && !Character.isLetterOrDigit(token.charAt(end - 1))) {
			end--;
		}
		return token.substring(end);
	}

	private static boolean anyFixed(boolean[] fixed, int from, int n) {
		for (int i = from; i < from + n; i++) {
			if (fixed[i]) {
				return true;
			}
		}
		return false;
	}

	private static boolean[] remove(boolean[] fixed, int at) {
		boolean[] out = new boolean[fixed.length - 1];
		System.arraycopy(fixed, 0, out, 0, at);
		System.arraycopy(fixed, at + 1, out, at, fixed.length - at - 1);
		return out;
	}

	private static boolean[] insert(boolean[] fixed, int at) {
		boolean[] out = new boolean[fixed.length + 1];
		System.arraycopy(fixed, 0, out, 0, at);
		out[at] = true;
		System.arraycopy(fixed, at, out, at + 1, fixed.length - at);
		return out;
	}
}
