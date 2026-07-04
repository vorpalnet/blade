package org.vorpal.blade.framework.v3.configuration;

import java.net.URI;
import java.net.URISyntaxException;

/// A [Resolvable] that produces a `java.net.URI` for HTTP(S) endpoints —
/// e.g. an OAuth2 `tokenUrl` that contains `${var}`. Uses `java.net.URI`
/// (not `java.net.URL`, whose `equals`/`hashCode` do blocking DNS lookups) as
/// the value type; call `.toURL()` at connect time. SIP [UriTidy] is not
/// applied — its rules are SIP-specific.
public class ResolvableHttpAddress extends Resolvable<URI> {
	private static final long serialVersionUID = 1L;

	public ResolvableHttpAddress() {
	}

	public ResolvableHttpAddress(String template) {
		super(template);
	}

	@Override
	protected URI parse(String resolved) {
		try {
			return new URI(resolved.trim());
		} catch (URISyntaxException e) {
			throw new BladeUriException(getTemplate(), resolved, e);
		}
	}
}
