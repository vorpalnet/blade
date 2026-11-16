package org.vorpal.blade.framework.v2.transfer;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.Transfer]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class Transfer extends org.vorpal.blade.framework.v3.transfer.Transfer {
	private static final long serialVersionUID = 1L;

	public Transfer(org.vorpal.blade.framework.v3.transfer.TransferListener transferListener, org.vorpal.blade.framework.v3.transfer.TransferSettings transferSettings) {
		super(transferListener, transferSettings);
	}
}
