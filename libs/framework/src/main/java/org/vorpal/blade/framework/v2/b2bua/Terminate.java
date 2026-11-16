package org.vorpal.blade.framework.v2.b2bua;

/// @deprecated Use [org.vorpal.blade.framework.v3.b2bua.Terminate]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class Terminate extends org.vorpal.blade.framework.v3.b2bua.Terminate {
	private static final long serialVersionUID = 1L;

	public Terminate(org.vorpal.blade.framework.v3.b2bua.B2buaListener b2buaListener) {
		super(b2buaListener);
	}
}
