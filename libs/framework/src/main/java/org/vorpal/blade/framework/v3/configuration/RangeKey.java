package org.vorpal.blade.framework.v3.configuration;

/// Helpers for the [MatchStrategy#range] key format.
///
/// A range key is `"lo-hi"` — two integers separated by a single dash,
/// inclusive of both endpoints. Examples: `"0-7"`, `"8-17"`,
/// `"100-199"`. Whitespace around either number is tolerated.
///
/// Negative ranges are not supported in this format (the dash is
/// ambiguous with a sign). Tables that need negative ranges can split
/// into two entries or rephrase the key expression.
public final class RangeKey {
	private RangeKey() {
	}

	/// Returns true if `n` falls within the inclusive range encoded by
	/// `rangeKey`. Malformed keys return false — silent miss rather than
	/// exception so one bad entry can't derail a whole lookup.
	public static boolean contains(String rangeKey, long n) {
		if (rangeKey == null) return false;
		int dash = rangeKey.indexOf('-');
		if (dash <= 0 || dash >= rangeKey.length() - 1) return false;
		try {
			long lo = Long.parseLong(rangeKey.substring(0, dash).trim());
			long hi = Long.parseLong(rangeKey.substring(dash + 1).trim());
			return n >= lo && n <= hi;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
