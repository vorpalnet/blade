package org.vorpal.blade.library.stir;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.List;

/// Resolves an `x5u` URI to its X.509 certificate chain.
///
/// The spike does not implement a real HTTP fetch — tests inject an
/// in-memory implementation. `services/stir-vs/` will add a Coherence-
/// backed HTTP resolver in Week 2.
public interface CertResolver {

	/// @return cert chain leaf-first; never null, never empty.
	List<X509Certificate> resolve(URI x5u) throws Exception;
}
