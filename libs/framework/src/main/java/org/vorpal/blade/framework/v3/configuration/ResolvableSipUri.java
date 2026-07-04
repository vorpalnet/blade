package org.vorpal.blade.framework.v3.configuration;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// A [Resolvable] that produces a `javax.servlet.sip.SipURI`. Applies
/// [UriTidy] to the substituted string before parsing, so empty `${var}`
/// substitutions don't leave a malformed URI.
public class ResolvableSipUri extends Resolvable<SipURI> {
	private static final long serialVersionUID = 1L;

	public ResolvableSipUri() {
	}

	public ResolvableSipUri(String template) {
		super(template);
	}

	@Override
	protected SipURI parse(String resolved) {
		String tidied = UriTidy.tidy(resolved);
		try {
			return (SipURI) SettingsManager.sipFactory.createURI(tidied);
		} catch (ServletParseException | ClassCastException e) {
			throw new BladeUriException(getTemplate(), tidied, e);
		}
	}
}
