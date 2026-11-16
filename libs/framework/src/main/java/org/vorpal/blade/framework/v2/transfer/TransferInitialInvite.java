package org.vorpal.blade.framework.v2.transfer;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.TransferInitialInvite]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class TransferInitialInvite extends org.vorpal.blade.framework.v3.transfer.TransferInitialInvite {
	private static final long serialVersionUID = 1L;

	public TransferInitialInvite(org.vorpal.blade.framework.v3.b2bua.B2buaListener b2buaListener) {
		super(b2buaListener);
	}
}
