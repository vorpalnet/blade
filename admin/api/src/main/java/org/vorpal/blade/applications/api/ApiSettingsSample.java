package org.vorpal.blade.applications.api;

/// Default configuration for the API Explorer. The `name` / `tagline` /
/// `description` drive the app's card on the Admin Portal launcher deck.
public class ApiSettingsSample extends ApiSettings {
	private static final long serialVersionUID = 1L;

	public ApiSettingsSample() {
		this.engineBaseUrl = "http://localhost:8001";
	}
}
