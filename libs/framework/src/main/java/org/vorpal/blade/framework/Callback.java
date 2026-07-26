package org.vorpal.blade.framework;

import java.io.Serializable;
import java.util.function.Consumer;

/// A serializable functional interface for SIP callflow callbacks that can throw
/// checked exceptions. Extends [Consumer] so it is a natural lambda target, and
/// [Serializable] so a continuation rides the `SipApplicationSession` through
/// cluster failover — the mechanism BLADE's lambda callflows are built on.
///
/// This is the version-neutral **baseline** type. Both the v2 face
/// ([org.vorpal.blade.framework.v2.callflow.Callback]) and the v3 callflow API
/// resolve to this single definition, so a callback created against one line
/// interoperates with the other. Application code binds to a versioned face,
/// never to this type directly.
///
/// @param <T> the callback input, typically a `SipServletRequest` or `SipServletResponse`
@FunctionalInterface
public interface Callback<T> extends Consumer<T>, Serializable {

	/// Wraps [#acceptThrows] to satisfy [Consumer#accept]. Null elements are ignored.
	@Override
	default void accept(final T elem) {
		try {
			if (elem != null) {
				acceptThrows(elem);
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/// Performs this callback operation on the given element.
	///
	/// @param t the input element
	/// @throws Exception if the callback operation fails
	void acceptThrows(T t) throws Exception;
}
