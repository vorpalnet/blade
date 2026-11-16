package org.vorpal.blade.framework.v2.b2bua;

/// @deprecated Use [org.vorpal.blade.framework.v3.b2bua.Cancel]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class Cancel extends org.vorpal.blade.framework.v3.b2bua.Cancel {
	private static final long serialVersionUID = 1L;

	public Cancel() {
		super();
	}

	public Cancel(org.vorpal.blade.framework.v3.b2bua.B2buaListener b2buaListener) {
		super(b2buaListener);
	}
}
