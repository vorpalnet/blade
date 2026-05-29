package org.vorpal.blade.applications.api;

/// Default configuration for the API Explorer. The `name` / `tagline` /
/// `description` drive the app's card on the Admin Portal launcher deck.
public class ApiSettingsSample extends ApiSettings {
	private static final long serialVersionUID = 1L;

	public ApiSettingsSample() {
		this.about.setName("API Explorer")
				.setTagline("Live OpenAPI Reference")
				.setDescription("Discovers every deployed BLADE service that publishes an OpenAPI document and renders it with an interactive, try-it-out reference. Pick a service from the pulldown, or deep-link straight to one with ?app=<service>.");
		this.engineBaseUrl = "http://localhost:8001";
	}
}
