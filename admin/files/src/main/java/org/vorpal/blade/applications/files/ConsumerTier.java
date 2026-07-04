package org.vorpal.blade.applications.files;

/// Which server tier actually *reads* a registry file — the property that
/// decides which restart (if any) applies an edit.
///
/// Editing a domain descriptor by hand only takes effect when the server that
/// consumes it re-reads it, and for the AdminServer-owned descriptors that
/// happens at boot. Worse, the running AdminServer holds its config in memory
/// and can flush it back over a hand-edit on its next save — so a hand-edit to
/// an `ADMIN` file is racy until the server is bounced. This enum lets the
/// editor offer the *right* restart after a save instead of a blunt one that
/// would help some files and silently do nothing for others.
public enum ConsumerTier {
	/// No restart needed — the file is re-read on demand, or nothing consumes
	/// it at runtime. The editor offers no restart.
	NONE,

	/// Read by the AdminServer (e.g. `config.xml`, `lifecycle-config.xml`).
	/// Applying an edit means restarting the AdminServer — which takes the whole
	/// admin tier offline. This is the case the restart button handles today.
	ADMIN,

	/// Read by the SIP engine tier (e.g. `approuter.xml`, `sipserver.xml`).
	/// Restarting the AdminServer does NOT apply it: the file must be pushed to
	/// the engine nodes and those bounced (the paused cluster-file-sync work).
	/// The editor says so rather than offering a restart that wouldn't help.
	ENGINE,

	/// Read by both tiers (e.g. a shared Coherence descriptor). Applying an edit
	/// needs both an AdminServer restart and an engine-tier apply.
	BOTH
}
