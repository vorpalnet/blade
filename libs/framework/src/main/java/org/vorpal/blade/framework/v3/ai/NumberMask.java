package org.vorpal.blade.framework.v3.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Swaps the phone numbers in text sent to a model for stand-ins, and back in
/// what it returns. A number is a run of seven or more digits: long enough for
/// a phone number, too long for a port, a CSeq or an octet of an address. Each
/// distinct number gets one stand-in of the same length, built so it cannot
/// equal a number in the text, so a pattern the model writes against the
/// stand-in (`\d{10}`, a prefix) still fits the real number.
///
/// One mask per conversation: what is masked in any message is unmasked the
/// same way in every answer.
public final class NumberMask {

	private static final Pattern NUMBER = Pattern.compile("\\d{7,}");

	private final Map<String, String> toStandIn = new HashMap<>();
	private final Map<String, String> toReal = new HashMap<>();
	private int next = 1;

	/// `text` with every number replaced by its stand-in.
	public String mask(String text) {
		if (text == null) return null;
		Matcher m = NUMBER.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(out, Matcher.quoteReplacement(standIn(m.group())));
		}
		m.appendTail(out);
		return out.toString();
	}

	/// `text` with every stand-in put back as the number it stands for.
	public String unmask(String text) {
		if (text == null || toReal.isEmpty()) return text;
		Matcher m = NUMBER.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String real = toReal.get(m.group());
			m.appendReplacement(out, Matcher.quoteReplacement(real != null ? real : m.group()));
		}
		m.appendTail(out);
		return out.toString();
	}

	/// How many numbers have been masked.
	public int size() {
		return toStandIn.size();
	}

	private String standIn(String real) {
		String known = toStandIn.get(real);
		if (known != null) return known;
		// 5550000…N, the same length: the 555 exchange is reserved for fiction.
		String fake;
		do {
			String n = String.valueOf(next++);
			StringBuilder b = new StringBuilder("555");
			while (b.length() + n.length() < real.length()) b.append('0');
			b.append(n);
			fake = (b.length() > real.length()) ? b.substring(b.length() - real.length()) : b.toString();
		} while (toReal.containsKey(fake) || toStandIn.containsKey(fake));
		toStandIn.put(real, fake);
		toReal.put(fake, real);
		return fake;
	}
}
