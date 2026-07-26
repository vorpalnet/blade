package org.vorpal.blade.framework.v3;

/// The v3 face of the baseline [org.vorpal.blade.framework.Callback].
///
/// A serializable functional interface for SIP callflow callbacks that can throw
/// checked exceptions — the lambda continuation type the v3 callflow API is built
/// on. Its definition lives in the version-neutral baseline; this is the name a
/// v3 application binds to so its imports stay `v3.*`. The single abstract method
/// (`acceptThrows`) and the `accept` default are inherited from the baseline, and
/// because the baseline `Callflow.sendRequest`/`sendResponse` are typed to the
/// baseline interface, a `v3.Callback` (a subtype) is accepted everywhere they are.
///
/// @param <T> the callback input, typically a `SipServletRequest` or `SipServletResponse`
@FunctionalInterface
public interface Callback<T> extends org.vorpal.blade.framework.Callback<T> {
}
