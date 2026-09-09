package org.vorpal.blade.applications.catalog;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// What the catalog needs to know, which is little: where the rows go and the
/// key that lets a protected value be searched for without being stored.
public class CatalogSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String dataSource = "jdbc/BladeAnalytics";
	private String protectedValueKey = "";
	private boolean fullText = true;
	private int rebuildDays = 0;

	@JsonPropertyDescription("JNDI name of the datasource the catalog writes to. The review application reads the same "
			+ "tables through the same name, so the datasource must be targeted at the admin server as well as the "
			+ "engine tier.")
	public String getDataSource() {
		return dataSource;
	}

	public void setDataSource(String dataSource) {
		this.dataSource = dataSource;
	}

	@JsonPropertyDescription("Secret key for the keyed hash of every protected value the redactor found (card, member "
			+ "id, phone, ...). The catalog stores the hash and never the value, so a reviewer holding phi:unredact can "
			+ "ask for the calls in which a given member id was spoken, and a copy of the catalog yields nothing. "
			+ "The review application must be configured with the same key. Empty disables protected-value search.")
	public String getProtectedValueKey() {
		return protectedValueKey;
	}

	public void setProtectedValueKey(String protectedValueKey) {
		this.protectedValueKey = (protectedValueKey == null) ? "" : protectedValueKey;
	}

	@JsonPropertyDescription("Build a full-text index on the utterance text (Oracle Text) so a search for words is an "
			+ "index lookup. When the database cannot create one, the catalog says so in the log and the review "
			+ "application falls back to a scan, which is correct and slow.")
	public boolean isFullText() {
		return fullText;
	}

	public void setFullText(boolean fullText) {
		this.fullText = fullText;
	}

	@JsonPropertyDescription("On start, re-index the conversations of the last N days from the archive, oldest first, "
			+ "in the background. The catalog is a cache of the archive, and this is how it is filled for the calls "
			+ "that closed before it existed, or after a schema change. 0 leaves the catalog to the events alone.")
	public int getRebuildDays() {
		return rebuildDays;
	}

	public void setRebuildDays(int rebuildDays) {
		this.rebuildDays = Math.max(0, rebuildDays);
	}
}
