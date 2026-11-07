package org.vorpal.blade.framework.v3.crud;

/// A configuration that carries a [Crud] section for its application to run.
///
/// The v3 [org.vorpal.blade.framework.v3.configuration.Configuration]
/// implements it. It is an interface, not only that base class, because some
/// settings classes must keep a v2 parent: the v2 transfer callflows take the
/// v2 `TransferSettings`, so the v3
/// [org.vorpal.blade.framework.v3.transfer.TransferSettings] extends that and
/// implements this instead.
public interface CrudCapable {

	/// The application's `crud` section, or null for none.
	Crud getCrud();
}
