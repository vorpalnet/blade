package org.vorpal.blade.services.queue.config;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class QueueAttributes implements Serializable {
	private static final long serialVersionUID = 1L;

	public Integer period;

	public Integer rate;
//	public Integer limit;

	public Integer ringDuration;

	public Integer ringPeriod;

	public String announcement;

	public String media;

	public Boolean destinationHolds;

	public QueueAttributes() {
	}

	public QueueAttributes(QueueAttributes that) {
		this.period = that.period;
		this.rate = that.rate;
//		this.limit = that.limit;
		this.ringDuration = that.ringDuration;
		this.ringPeriod = that.ringPeriod;
		this.announcement = that.announcement;
		this.media = that.media;
		this.destinationHolds = that.destinationHolds;
	}

	@JsonPropertyDescription("Milliseconds between release ticks.")
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

	@JsonPropertyDescription("Calls released per tick, oldest first.")
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

	@JsonPropertyDescription("Seconds between the 180 Ringing re-sent to a waiting caller.")
	public Integer getRingPeriod() {
		return ringPeriod;
	}

	public QueueAttributes setRingPeriod(Integer ringPeriod) {
		this.ringPeriod = ringPeriod;
		return this;
	}

	@JsonPropertyDescription("Seconds a caller hears ringing before the queue answers with the announcement or media. "
			+ "0 or empty answers at once when either is set.")
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

	@JsonPropertyDescription("SIP URI of an announcement server the caller is connected to while waiting. Ignored when "
			+ "media is set.")
	public String getAnnouncement() {
		return announcement;
	}

	public QueueAttributes setAnnouncement(String announcement) {
		this.announcement = announcement;
		return this;
	}

	@JsonPropertyDescription("A prompt or music the queue plays to a waiting caller itself, on the JSR-309 media "
			+ "server, looping until the caller is released. An http: or file: URI the media server can fetch. "
			+ "Needs the media server driver configured; takes the place of announcement.")
	public String getMedia() {
		return media;
	}

	public QueueAttributes setMedia(String media) {
		this.media = media;
		return this;
	}

	@JsonPropertyDescription("The destination keeps its own line, as presence does for a skill's address: "
			+ "each caller is sent on as soon as they hear the media, without waiting for a release tick, "
			+ "and keeps hearing it until the destination answers. Needs media. Off: callers wait for the "
			+ "queue's ticks.")
	public Boolean getDestinationHolds() {
		return destinationHolds;
	}

	public QueueAttributes setDestinationHolds(Boolean destinationHolds) {
		this.destinationHolds = destinationHolds;
		return this;
	}

}
