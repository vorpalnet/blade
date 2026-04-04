package org.vorpal.blade.services.queue.config;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class QueueAttributes implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Interval in milliseconds between polling the queue for new calls.")
	public Integer period;

	@JsonPropertyDescription("Number of transactions processed per polling cycle.")
	public Integer rate;
//	public Integer limit;

	@JsonPropertyDescription("Duration in milliseconds to wait for an announcement or agent response before re-queuing the call.")
	public Integer ringDuration;

	@JsonPropertyDescription("Interval in milliseconds between ring attempts to an agent.")
	public Integer ringPeriod;

	@JsonPropertyDescription("SIP URI of the announcement server to play while callers wait in the queue.")
	public String announcement;

	public QueueAttributes() {
	}

	public QueueAttributes(QueueAttributes that) {
		this.period = that.period;
		this.rate = that.rate;
//		this.limit = that.limit;
		this.ringDuration = that.ringDuration;
		this.announcement = that.announcement;
	}

	public Integer getPeriod() {
		return period;
	}

	/**
	 * Set number of milliseconds between polling the queue.
	 * 
	 * @param period
	 * @return
	 */
	public QueueAttributes setPeriod(Integer period) {
		this.period = period;
		return this;
	}

	public Integer getRate() {
		return rate;
	}

	/**
	 * Set number transactions processed per polling cycle.
	 * 
	 * @param rate
	 * @return
	 */
	public QueueAttributes setRate(Integer rate) {
		this.rate = rate;
		return this;
	}

//	public Integer getLimit() {
//		return limit;
//	}
//
//	public QueueAttributes setLimit(Integer limit) {
//		this.limit = limit;
//		return this;
//	}

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

	/**
	 * Sets the number of milliseconds to wait for an announcement or agent response
	 * before placing the call back in the queue.
	 * 
	 * @param ringDuration
	 * @return
	 */
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
