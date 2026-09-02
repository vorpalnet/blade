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
/// @deprecated Import the baseline [org.vorpal.blade.framework.Callback] instead. There is
///             one callback type; this name adds nothing but a second way to spell it, and
///             the two generations are collapsing onto the baseline. Nothing in this
///             repository imports this face. It stays so any application that
///             does keeps compiling — and so a serialized lambda naming this interface
///             still resolves on failover.
@Deprecated
@FunctionalInterface
public interface Callback<T> extends org.vorpal.blade.framework.Callback<T> {
}
