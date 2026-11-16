package org.vorpal.blade.framework.v2.transfer;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.ReferTransfer]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class ReferTransfer extends org.vorpal.blade.framework.v3.transfer.ReferTransfer {
	private static final long serialVersionUID = 1L;

	public ReferTransfer(org.vorpal.blade.framework.v3.transfer.TransferListener referListener, org.vorpal.blade.framework.v3.transfer.TransferSettings transferSettings) {
		super(referListener, transferSettings);
	}
}
