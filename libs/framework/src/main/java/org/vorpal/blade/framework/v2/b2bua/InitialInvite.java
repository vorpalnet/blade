package org.vorpal.blade.framework.v2.b2bua;

/// @deprecated Use [org.vorpal.blade.framework.v3.b2bua.InitialInvite]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class InitialInvite extends org.vorpal.blade.framework.v3.b2bua.InitialInvite {
	private static final long serialVersionUID = 1L;

	public InitialInvite() {
		super();
	}

	public InitialInvite(org.vorpal.blade.framework.v3.b2bua.B2buaListener b2buaListener) {
		super(b2buaListener);
	}
}
