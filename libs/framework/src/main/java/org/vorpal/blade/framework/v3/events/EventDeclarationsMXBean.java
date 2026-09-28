package org.vorpal.blade.framework.v3.events;

/// One application's own event types, as its WAR declares them in
/// `WEB-INF/blade-events.json`. See [EventDeclarations].
public interface EventDeclarationsMXBean {

	/// The declaring application: its deployment name.
	String getApplication();

	/// The declarations as JSON: an object whose `types` list reads as an
	/// [EventCatalog]'s.
	String getCatalog();

	/// How many types the application declares.
	int getTypeCount();
}
