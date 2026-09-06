package org.vorpal.blade.applications.audit;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.security.AccessPolicy;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@SchemaAbout(
		name = "Audit",
		tagline = "Access Log",
		description = "Keeps a record of every access decision, and decides who may read that record back. "
				+ "Writing the log needs no policy; reading it needs phi:audit, and an empty rule list "
				+ "refuses everything.")
public class AuditSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private AccessPolicy access = new AccessPolicy();

	@JsonPropertyDescription("Who may read the access log. Grant phi:audit to the people who audit and not to "
			+ "the people being audited: a log its subjects can read is a map of what they got away with. "
			+ "An empty list refuses everything, which is what an unconfigured deployment should do.")
	public AccessPolicy getAccess() {
		return access;
	}

	public void setAccess(AccessPolicy access) {
		this.access = access;
	}
}
