package org.vorpal.blade.applications.phone;

/// Default configuration written on first deployment.
///
/// The gateway is left blank on purpose: an address guessed from the admin
/// server's own hostname is right on a single-box install and wrong on every
/// clustered one, and a blank field an operator must fill is easier to diagnose
/// than a plausible address that points at nothing.
public class PhoneSettingsSample extends PhoneSettings {
	private static final long serialVersionUID = 1L;

	public PhoneSettingsSample() {
		setAorDomain("vorpal.net");
	}
}
