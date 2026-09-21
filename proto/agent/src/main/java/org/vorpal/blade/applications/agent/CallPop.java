package org.vorpal.blade.applications.agent;

/// Everything the console shows the agent about one arriving call, serialized to
/// JSON and pushed over the WebSocket. Built once from the INVITE (see
/// [CallPopBuilder]) and enriched with catalog [CallerHistory]; the risk band is
/// filled in later if a risk consumer supplies one.
///
/// Plain public fields: Jackson serializes it as-is.
public final class CallPop {

	public String callId;
	public String ani;
	public String displayName;
	public String dialed;

	/// STIR/SHAKEN, straight off the INVITE (decoded, not verified).
	public String verstat;
	public String attestation;
	public Long stirAgeSeconds;
	public boolean anonymous;

	/// The upstream screening verdict (proxy-block / iRouter), if present.
	public String screenVerdict;
	public String screenReason;

	/// This number's per-node call rate the edge measured, if present.
	public Integer callRate;

	/// What the catalog knows about this caller.
	public CallerHistory history = CallerHistory.EMPTY;

	/// The fused risk band, when a risk consumer has supplied one; null pre-fusion.
	public String riskBand;

	/// The fused risk score behind the band (a number, so it carries order the
	/// band word alone does not); null when the call carries no score.
	public String riskScore;

	public String receivedUtc;
}
