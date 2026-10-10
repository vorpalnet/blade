package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// One application counter that moved in one sample, from `v_app_counters`.
/// A labelled counter is `name.value`. See [CallRow] for why these map views.
@Entity
@Table(name = "v_app_counters")
public class CounterRow implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The sample's event id and the counter name: one sample carries many.
	@Id
	@Column(name = "row_id")
	private String rowId;

	@Convert(converter = UtcTimestamp.class)
	@Column(name = "sampled_at")
	private Date sampledAt;

	private String application;

	private String node;

	/// The WebLogic domain of the server that published the sample.
	private String domain;

	private String counter;

	private Long delta;
}
