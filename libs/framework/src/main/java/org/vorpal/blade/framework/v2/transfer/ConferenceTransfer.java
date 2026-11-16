package org.vorpal.blade.framework.v2.transfer;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.ConferenceTransfer]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class ConferenceTransfer extends org.vorpal.blade.framework.v3.transfer.ConferenceTransfer {
	private static final long serialVersionUID = 1L;

	public ConferenceTransfer(org.vorpal.blade.framework.v3.transfer.TransferListener referListener, org.vorpal.blade.framework.v3.transfer.TransferSettings transferSettings) {
		super(referListener, transferSettings);
	}
}
