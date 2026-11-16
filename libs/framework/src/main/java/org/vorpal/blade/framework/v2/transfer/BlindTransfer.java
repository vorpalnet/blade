package org.vorpal.blade.framework.v2.transfer;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.BlindTransfer]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class BlindTransfer extends org.vorpal.blade.framework.v3.transfer.BlindTransfer {
	private static final long serialVersionUID = 1L;

	public BlindTransfer(org.vorpal.blade.framework.v3.transfer.TransferListener referListener, org.vorpal.blade.framework.v3.transfer.TransferSettings transferSettings) {
		super(referListener, transferSettings);
	}

	public BlindTransfer(org.vorpal.blade.framework.v3.transfer.TransferListener referListener, org.vorpal.blade.framework.v3.transfer.TransferSettings transferSettings, boolean sendNotify) {
		super(referListener, transferSettings, sendNotify);
	}
}
