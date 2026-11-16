package org.vorpal.blade.framework.v2.b2bua;

/// @deprecated Use [org.vorpal.blade.framework.v3.b2bua.Reinvite]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class Reinvite extends org.vorpal.blade.framework.v3.b2bua.Reinvite {
	private static final long serialVersionUID = 1L;

	public Reinvite() {
		super();
	}

	public Reinvite(org.vorpal.blade.framework.v3.b2bua.B2buaListener b2buaListener) {
		super(b2buaListener);
	}
}
