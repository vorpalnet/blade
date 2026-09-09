package org.vorpal.blade.framework.v3.media.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// An interval of a track that carries no media, and why.
///
/// A gap is how a preserved timeline stays cheap. The media is simply absent
/// across the interval, every later offset stays true, and a player renders
/// silence. Nothing is written to storage for the duration of a hold.
///
/// ## A gap is evidence, not an omission
///
/// The reason matters more than the interval. Recording that audio is missing
/// from 8.2s to 11.4s says little; recording that it is missing *because the
/// call was on hold* says the recording is intact and the policy worked. The
/// same interval with reason [Reason#LOSS] says the opposite. Without the
/// reason a reviewer cannot tell a working control from a broken recorder.
///
/// ## The one thing that is genuinely destroyed
///
/// [Reason#PCI] is the exception to preserving detail. Card digits must not be
/// retained, so the audio for that interval is never stored and there is nothing
/// behind the gap for `phi:unredact` to reveal. The interval, its bounds and the
/// fact that suppression was applied are all kept, which is what lets an auditor
/// see that nothing went missing by accident while the protected content
/// genuinely does not exist.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaGap {

	/// Why a stretch of a track carries no media.
	public enum Reason {
		/// The party was on hold. The recording is intact; the control worked.
		HOLD,
		/// Card entry. The audio was never stored. See the class note.
		PCI,
		/// The node recording this track was lost and another took over.
		FAILOVER,
		/// Media stopped arriving. A fault, not a control.
		LOSS,
		/// Suppressed by policy for a reason other than card entry.
		POLICY,
		/// The party moved its media to a new address mid-call and its leg was
		/// rebuilt on the media server: the gap is the rebuild, a few tens of
		/// milliseconds, and the timeline on either side of it is intact.
		MOVED
	}

	private Long startMillis;
	private Long endMillis;
	private Reason reason;
	private String detail;

	public MediaGap() {
	}

	public MediaGap(long startMillis, long endMillis, Reason reason) {
		this.startMillis = startMillis;
		this.endMillis = endMillis;
		this.reason = reason;
	}

	@JsonPropertyDescription("Start of the gap relative to the conversation epoch, in milliseconds.")
	public Long getStartMillis() {
		return startMillis;
	}

	public void setStartMillis(Long startMillis) {
		this.startMillis = startMillis;
	}

	@JsonPropertyDescription("End of the gap relative to the conversation epoch, in milliseconds.")
	public Long getEndMillis() {
		return endMillis;
	}

	public void setEndMillis(Long endMillis) {
		this.endMillis = endMillis;
	}

	@JsonPropertyDescription("Why no media was captured across this interval.")
	public Reason getReason() {
		return reason;
	}

	public void setReason(Reason reason) {
		this.reason = reason;
	}

	@JsonPropertyDescription("Anything further worth recording about this gap.")
	public String getDetail() {
		return detail;
	}

	public void setDetail(String detail) {
		this.detail = detail;
	}

	public long lengthMillis() {
		if (startMillis == null || endMillis == null) {
			return 0;
		}
		return Math.max(endMillis - startMillis, 0);
	}

	/// Whether this gap is a fault rather than a control working as intended.
	/// Hold, card entry and policy are expected; lost media and a lost node are
	/// not, and only the second kind makes a conversation incomplete.
	public boolean isFault() {
		return reason == Reason.LOSS || reason == Reason.FAILOVER;
	}

	public boolean covers(long millis) {
		return startMillis != null && endMillis != null && millis >= startMillis && millis < endMillis;
	}
}
