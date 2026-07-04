package org.vorpal.blade.framework.v3.configuration;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// A [Resolvable] that produces a `javax.servlet.sip.Address` (a name-addr,
/// possibly angle-bracketed with a display name). Applies [UriTidy] to the
/// substituted string before parsing.
public class ResolvableSipAddress extends Resolvable<Address> {
	private static final long serialVersionUID = 1L;

	public ResolvableSipAddress() {
	}

	public ResolvableSipAddress(String template) {
		super(template);
	}

	@Override
	protected Address parse(String resolved) {
		String tidied = UriTidy.tidy(resolved);
		try {
			return SettingsManager.sipFactory.createAddress(tidied);
		} catch (ServletParseException e) {
			throw new BladeUriException(getTemplate(), tidied, e);
		}
	}
}
