/// **Deprecated.** The B2BUA callflows (`InitialInvite`, `Reinvite`, `Terminate`,
/// `Bye`, `Cancel`, `Passthru`) and [B2buaListener] moved to
/// [org.vorpal.blade.framework.v3.b2bua], and applications extend
/// [org.vorpal.blade.framework.v3.B2buaServlet]. Each callflow name here is the v3
/// class under its old name, an empty subclass kept so code that names it still
/// compiles. [B2buaServlet] and [B2buaConfiguration] are the v2 originals, kept
/// for applications outside this workspace.
package org.vorpal.blade.framework.v2.b2bua;
