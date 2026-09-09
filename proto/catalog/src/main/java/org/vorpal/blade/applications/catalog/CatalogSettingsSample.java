package org.vorpal.blade.applications.catalog;

/// The settings a fresh deployment starts with: the shared analytics
/// datasource, full text on, and no protected-value key, so that search stays
/// off until an operator deliberately mints a key and gives the same one to
/// the review application.
public class CatalogSettingsSample extends CatalogSettings {
	private static final long serialVersionUID = 1L;

	public CatalogSettingsSample() {
		setDataSource("jdbc/BladeAnalytics");
		setFullText(true);
		setProtectedValueKey("");
	}
}
