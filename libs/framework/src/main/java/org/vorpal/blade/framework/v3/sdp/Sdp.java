package org.vorpal.blade.framework.v3.sdp;

/// The v3 face of [org.vorpal.blade.framework.v2.sdp.Sdp].
///
/// A parsed, mutable SDP body. The implementation lives in the frozen v2 class
/// (unchanged, so serialized SDP call-state deserializes across upgrades exactly
/// as before); this is the name a v3 application binds to so its imports stay
/// `v3.*`. Construct with `new Sdp()` and use the inherited accessors/mutators.
///
/// Note: v2's static parse helpers return the v2 type, so if you capture one in
/// an explicitly-typed variable it is a `v2.sdp.Sdp` (a supertype of this). Only
/// matters if you name that variable's type — a `new v3.sdp.Sdp()` never does.
public class Sdp extends org.vorpal.blade.framework.v2.sdp.Sdp {

	private static final long serialVersionUID = 1L;

	public Sdp() {
		super();
	}
}
