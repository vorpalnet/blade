package org.vorpal.blade.applications.javadoc;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/javadoc.json` on first deployment when no
/// operator-supplied file is present. Only metadata — the card name/tagline/
/// description the portal shows.
public class JavadocSettingsSample extends JavadocSettings {
	private static final long serialVersionUID = 1L;

	public JavadocSettingsSample() {
	}
}
