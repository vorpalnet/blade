package org.vorpal.blade.framework.v3.net;

/// The services an application calls, as it has found them ([Remotes]).
public interface RemotesMXBean {

	/// Every remote: `kind`, `remote`, `calls`, `failures`, `consecutive`
	/// (failures since the last success), `avgMs`, `lastSuccessAt`,
	/// `lastFailureAt` (epoch millis, 0 for never), `lastFailure` and `open`
	/// (a circuit breaker has it cut off).
	String getRemotesJson();
}
