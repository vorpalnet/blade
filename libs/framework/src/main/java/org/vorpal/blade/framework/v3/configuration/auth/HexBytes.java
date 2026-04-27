package org.vorpal.blade.framework.v3.configuration.auth;

/// Package-private byte-array → lowercase hex helper, used by HMAC and
/// SigV4 authentication for signature encoding. Kept in one place so
/// both subtypes produce identical output format.
final class HexBytes {
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private HexBytes() {
	}

	static String toHex(byte[] data) {
		if (data == null) return "";
		char[] out = new char[data.length * 2];
		for (int i = 0, j = 0; i < data.length; i++) {
			int b = data[i] & 0xff;
			out[j++] = HEX[b >>> 4];
			out[j++] = HEX[b & 0x0f];
		}
		return new String(out);
	}
}
