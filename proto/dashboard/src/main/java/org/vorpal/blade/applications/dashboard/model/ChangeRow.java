package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// One configuration change, from `v_config_changes`: a file the Configurator
/// pushed to the servers running its application (`configPublished`), or one
/// saved from an editor (`configSaved`). Like [SampleRow] it carries the
/// publishing server's domain, not a `cluster_name`. See [CallRow] for why
/// these map views.
@Entity
@Table(name = "v_config_changes")
public class ChangeRow implements Serializable {
	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "event_id")
	private String eventId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "changed_at")
	private Date changedAt;

	/// `configPublished` or `configSaved`.
	@Column(name = "change_type")
	private String changeType;

	@Column(name = "config_file")
	private String configFile;

	/// The application the file configures.
	@Column(name = "configured_application")
	private String configuredApplication;

	/// Who saved it; null for a push.
	private String actor;

	/// For a save: saved, restored or deleted.
	@Column(name = "change_kind")
	private String changeKind;

	/// The servers that did not take a push, as a JSON array; null when every one did.
	private String failed;

	private String node;

	private String domain;
}
