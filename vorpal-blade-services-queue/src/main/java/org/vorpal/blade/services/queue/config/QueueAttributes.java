package org.vorpal.blade.services.queue.config;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

public class QueueAttributes implements Serializable {
	private static final long serialVersionUID = 1L;

	public Integer period;
	public Integer rate;
	public Integer limit;
	public Integer ringDuration;
	public Integer ringPeriod;
	public String announcement;

	public QueueAttributes() {
	}

	public QueueAttributes(QueueAttributes that) {
		this.period = that.period;
		this.rate = that.rate;
		this.limit = that.limit;
		this.ringDuration = that.ringDuration;
		this.announcement = that.announcement;
	}

	public Integer getPeriod() {
		return period;
	}

	public QueueAttributes setPeriod(Integer period) {
		this.period = period;
		return this;
	}

	public Integer getRate() {
		return rate;
	}

	public QueueAttributes setRate(Integer rate) {
		this.rate = rate;
		return this;
	}

	public Integer getLimit() {
		return limit;
	}

	public QueueAttributes setLimit(Integer limit) {
		this.limit = limit;
		return this;
	}

	public Integer getRingPeriod() {
		return ringPeriod;
	}

	public QueueAttributes setRingPeriod(Integer ringPeriod) {
		this.ringPeriod = ringPeriod;
		return this;
	}

	public Integer getRingDuration() {
		return ringDuration;
	}

	public QueueAttributes setRingDuration(Integer ringDuration) {
		this.ringDuration = ringDuration;
		return this;
	}

	public String getAnnouncement() {
		return announcement;
	}

	public QueueAttributes setAnnouncement(String announcement) {
		this.announcement = announcement;
		return this;
	}

}
