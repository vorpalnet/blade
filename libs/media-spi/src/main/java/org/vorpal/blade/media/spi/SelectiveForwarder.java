package org.vorpal.blade.media.spi;

import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.media.mscontrol.resource.Resource;

/// Forwards one participant's video to another without mixing it, so a conference can show every
/// participant in its own tile. This is the SFU primitive: the media server routes a sender's video
/// into a receiver's stream and never decodes or composites a picture.
///
/// ## Why this interface exists
///
/// JSR-309 mixes. Its [javax.media.mscontrol.mixer.MediaMixer] sums audio and, where a driver
/// supports it, composites video into one picture the server chose the layout of. A conference that
/// wants each client to lay out its own tiles, pin a speaker, and adapt to its own bandwidth needs
/// the opposite: the server forwards each sender's video untouched and the client decides what to do
/// with it. The 2009 spec has no verb for that, so a driver whose media server can forward implements
/// this, and the application finds it with `ResourceContainer.getResource(SelectiveForwarder.class)`,
/// the specification's own door for a resource it did not define. A driver whose media server cannot
/// is still a valid driver: `getResource` returns null and the conference falls back to a mix (or to
/// audio only), the way a missing Transcriber leaves a call with audio and no transcript.
///
/// ## Attached to the receiver
///
/// The forwarder is a resource of one [NetworkConnection], the participant who is going to *see* the
/// others. Its downlink is a set of named video receive-tracks, one per remote sender being shown;
/// each is a separate m-line the media server offers this participant. [#addVideoReceiveTrack] makes
/// one, [#routeVideo] attaches a source to it, [#removeVideoReceiveTrack] drops it when that sender
/// leaves. A sender's own uplink is its ordinary [NetworkConnection]; nothing special marks it as a
/// source.
///
/// ## The application holds the policy
///
/// This interface is only mechanism: add a track, route a source, drop a track. Which participants a
/// given viewer sees, how many tiles, who is promoted to the large one, which are dropped when the
/// meeting grows past what a screen or a downlink can carry, all of that is the conference
/// application's decision, made against its own roster. The media server forwards what it is told and
/// enforces the pipe (it may drop to a lower layer under congestion); it does not decide who is worth
/// watching. Keeping that split is what lets different conference applications, built by different
/// teams on this platform, each behave the way they choose.
///
/// ## Adding a track means renegotiating
///
/// A new receive-track is a new m-line, so after [#addVideoReceiveTrack] (or a
/// [#removeVideoReceiveTrack]) the receiver's SDP has changed and the application must re-offer it to
/// the browser, through the leg's [javax.media.mscontrol.networkconnection.SdpPortManager] and a
/// SIP re-INVITE. The media change and the SDP renegotiation are two steps, and an application that
/// admits or drops several participants at once should batch their track changes into one
/// renegotiation rather than one round per track.
public interface SelectiveForwarder extends Resource<NetworkConnection> {

	/// Add a video receive-track to this participant: a new downlink video m-line the media server
	/// will offer them. Returns an id naming the track, for [#routeVideo] and
	/// [#removeVideoReceiveTrack]. The receiver's SDP is now stale; re-offer it (see the interface
	/// note on renegotiating).
	String addVideoReceiveTrack() throws MsControlException;

	/// Forward `source`'s uplink video into the named receive-track on this participant. `source` is
	/// another participant's ordinary connection; `trackId` is a track from [#addVideoReceiveTrack]
	/// on this one. Re-routing a track to a different source replaces the previous one.
	void routeVideo(NetworkConnection source, String trackId) throws MsControlException;

	/// Remove the named receive-track (its sender left, or the application stopped showing them). The
	/// receiver's SDP is now stale; re-offer it. Unknown ids are ignored.
	void removeVideoReceiveTrack(String trackId) throws MsControlException;
}
