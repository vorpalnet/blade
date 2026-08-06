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
///
/// @deprecated Import [org.vorpal.blade.framework.v2.sdp.Sdp] — the one that holds the
///             implementation — until the SDP model moves to the baseline. This face
///             exists only so a v3 application's imports could stay `v3.*`, a distinction
///             the framework is collapsing. Nothing imports it in blade, optum, gryphon or
///             att-tao. It stays so any application that does keeps compiling, and so
///             serialized SDP call-state naming this class still resolves on failover.
@Deprecated
public class Sdp extends org.vorpal.blade.framework.v2.sdp.Sdp {

	private static final long serialVersionUID = 1L;

	public Sdp() {
		super();
	}
}
