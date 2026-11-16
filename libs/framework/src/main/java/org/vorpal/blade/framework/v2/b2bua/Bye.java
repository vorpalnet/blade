package org.vorpal.blade.framework.v2.b2bua;

/// @deprecated Use [org.vorpal.blade.framework.v3.b2bua.Bye]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class Bye extends org.vorpal.blade.framework.v3.b2bua.Bye {
	private static final long serialVersionUID = 1L;

	public Bye() {
		super();
	}

	public Bye(org.vorpal.blade.framework.v3.b2bua.B2buaListener b2buaListener) {
		super(b2buaListener);
	}
}
