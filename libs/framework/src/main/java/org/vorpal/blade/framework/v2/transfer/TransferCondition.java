package org.vorpal.blade.framework.v2.transfer;

/// @deprecated Use [org.vorpal.blade.framework.v3.transfer.TransferCondition]. This type is the v3 one under its old
///             name, kept so code that names it still compiles.
@Deprecated
public class TransferCondition extends org.vorpal.blade.framework.v3.transfer.TransferCondition {
	private static final long serialVersionUID = 1L;

	public TransferCondition() {
		super();
	}

	public TransferCondition(org.vorpal.blade.framework.v3.transfer.TransferSettings.TransferStyle style, org.vorpal.blade.framework.v2.config.Condition condition) {
		super(style, condition);
	}
}
