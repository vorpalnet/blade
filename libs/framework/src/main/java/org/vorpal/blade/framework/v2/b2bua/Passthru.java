package org.vorpal.blade.framework.v2.b2bua;

/// @deprecated Use [org.vorpal.blade.framework.v3.b2bua.Passthru]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class Passthru extends org.vorpal.blade.framework.v3.b2bua.Passthru {
	private static final long serialVersionUID = 1L;

	public Passthru() {
		super();
	}

	public Passthru(org.vorpal.blade.framework.v3.b2bua.B2buaListener b2buaListener) {
		super(b2buaListener);
	}
}
