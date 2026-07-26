package org.vorpal.blade.framework.v3.media;

/// Driver SPI (implemented by the [javax.media.mscontrol.MsControlFactory]) for injecting
/// **out-of-band DTMF** — digits that arrive in the *signaling* plane (a SIP INFO
/// `application/dtmf-relay` body) rather than in the media — into the media session's armed
/// [javax.media.mscontrol.mediagroup.signals.SignalDetector], so a [MediaCallflow#prompt] completes
/// uniformly no matter how the digits arrived.
///
/// This exists because the app is pure-309: it receives the INFO but can't reach a driver-specific
/// detector (309 has no standard "here is a signaled digit" call). The app therefore hands the digit
/// to [MediaCallflow#deliverDtmf], and the framework routes it to the driver through this hook —
/// exactly the app-aware split [MediaSessionRecovery] uses for failover. A driver whose detector is
/// fed only from the media plane (RFC 4733 / in-band tones) simply doesn't implement this.
public interface DtmfSink {

	/// Deliver `digits` to the SignalDetector currently collecting on the media session identified by
	/// `mediaSessionUri`. Returns true if a detector was armed and consumed them; false if nothing is
	/// collecting on that session (the digit is dropped — as an early, late, or duplicate INFO would
	/// be). `digits` is the raw DTMF string, e.g. `"5"` or `"1234"`.
	boolean deliverDtmf(String mediaSessionUri, String digits);
}
