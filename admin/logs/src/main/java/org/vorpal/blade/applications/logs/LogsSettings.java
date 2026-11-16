package org.vorpal.blade.applications.logs;

import org.vorpal.blade.framework.v3.access.Access;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.security.AccessPolicy;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Logs admin app: the launcher card's metadata, and who may
/// read log lines (`access`).
@SchemaAbout(
		name = "Logs",
		tagline = "Cluster Log Tail",
		description = "Stream and filter logs from every node in the cluster. Merges per-server output by timestamp; supports phase filters, severity filters, and live follow mode.")
public class LogsSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected AccessPolicy access = Access.defaultPolicy();

	@JsonPropertyDescription("Who may read log lines, which can hold caller numbers, account ids and SIP "
			+ "text. A rule with no match that grants phi:transcript lets its groups read every log; anyone "
			+ "else still sees the servers and the file names, and is refused the lines. The default lets the "
			+ "four WebLogic admin groups read. Remove Monitors from it to keep read-only staff out of caller data.")
	public AccessPolicy getAccess() {
		return access;
	}

	public LogsSettings setAccess(AccessPolicy access) {
		this.access = (access == null) ? new AccessPolicy() : access;
		return this;
	}
}
