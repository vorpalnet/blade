package org.vorpal.blade.applications.dashboard.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/// One application timing over one sample, from `v_app_timings`: how many
/// recordings and their summed milliseconds, so a mean over any window is the
/// sum of one over the sum of the other. See [CallRow] for why these map views.
@Entity
@Table(name = "v_app_timings")
public class TimingRow implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The sample's event id and the timing name: one sample carries many.
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

	private String timing;

	private Long recordings;

	@Column(name = "sum_millis")
	private Long sumMillis;
}
