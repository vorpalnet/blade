package org.vorpal.blade.library.stir;

/// Outcome of a PASSporT verification.
///
/// Either OK with the parsed `PassPort`, or a specific `Reason` plus a
/// human-readable diagnostic. Callflows branch on `reason`; logs render
/// `detail`.
public final class VerifyResult {

	public enum Reason {
		OK,
		MALFORMED_HEADER,
		MALFORMED_PASSPORT,
		MISSING_X5U,
		MISSING_PPT,
		BAD_ALG,
		UNTRUSTED_CHAIN,
		BAD_SIGNATURE,
		ORIG_TN_MISMATCH,
		IAT_EXPIRED,
		IAT_IN_FUTURE
	}

	private final Reason reason;
	private final PassPort passPort;
	private final String detail;

	private VerifyResult(Reason reason, PassPort passPort, String detail) {
		this.reason = reason;
		this.passPort = passPort;
		this.detail = detail;
	}

	public static VerifyResult ok(PassPort pp) {
		return new VerifyResult(Reason.OK, pp, null);
	}

	public static VerifyResult failure(Reason reason, String detail) {
		return new VerifyResult(reason, null, detail);
	}

	public boolean isOk()           { return reason == Reason.OK; }
	public Reason getReason()       { return reason; }
	public PassPort getPassPort()   { return passPort; }
	public String getDetail()       { return detail; }

	@Override
	public String toString() {
		return isOk() ? "VerifyResult{OK}"
				: "VerifyResult{" + reason + ": " + detail + "}";
	}
}
