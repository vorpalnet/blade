package org.vorpal.blade.applications.recordings;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.security.AccessPolicy;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the recordings review app: who may see and hear what.
///
/// The policy lives here rather than being read from the `security` app because
/// nothing yet distributes one policy across the admin tier. That is a known
/// gap, not a design: two apps enforcing two copies of a rule is exactly how
/// they come to disagree. `SECURITY.md` open item 3 is the same problem for the
/// JWT config, and both want the same answer.
@SchemaAbout(
		name = "Recordings",
		tagline = "Review & Access Control",
		description = "Who may list, hear, read or export call recordings. Every request is decided by the "
				+ "access policy below and recorded on the event bus, whichever way it goes. There is no "
				+ "'allow' default: an empty rule list refuses everything.")
public class RecordingsSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private AccessPolicy access = new AccessPolicy();
	private String catalogDataSource = "jdbc/BladeAnalytics";
	private String protectedValueKey = "";

	@JsonPropertyDescription("Who may see, hear, export or unredact recordings. An ordered list; the first rule that is about this caller, about this recording, and grants the permission asked for decides. An empty list refuses everything, which is what an unconfigured deployment should do.")
	public AccessPolicy getAccess() {
		return access;
	}

	@JsonPropertyDescription("JNDI name of the datasource holding the call catalog the blade-catalog application writes. "
			+ "Search reads it; listing and playback do not need it.")
	public String getCatalogDataSource() {
		return catalogDataSource;
	}

	public void setCatalogDataSource(String catalogDataSource) {
		this.catalogDataSource = catalogDataSource;
	}

	@JsonPropertyDescription("The same secret key blade-catalog uses to hash protected values. A search for a member "
			+ "id or card number hashes it here and matches the catalog's hashes; the value itself is stored nowhere. "
			+ "Empty disables that search.")
	public String getProtectedValueKey() {
		return protectedValueKey;
	}

	public void setProtectedValueKey(String protectedValueKey) {
		this.protectedValueKey = (protectedValueKey == null) ? "" : protectedValueKey;
	}

	public void setAccess(AccessPolicy access) {
		this.access = (access == null) ? new AccessPolicy() : access;
	}
}
