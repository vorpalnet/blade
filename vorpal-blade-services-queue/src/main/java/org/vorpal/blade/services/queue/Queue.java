package org.vorpal.blade.services.queue;

import javax.servlet.sip.URI;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Class to be used in the configuration file to define the traits of the queue.
 */
public class Queue {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The id of the queue.")
	private String id;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Timer period in milliseconds")
	private Integer period;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Allowed calls per period")
	private Integer rate;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Maximum calls allowed in queue. Zero, negative or NULL for unlimited.")
	private Integer limit;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Maximum time spent ringing in seconds. Zero, negative or NULL for no ring.")
	private Integer ringDuration;

	@JsonProperty(required = false)
	@JsonPropertyDescription("SIP URI for media server after ringing")
	private URI mediaServer;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Instead of media, place call on mute?")
	private Boolean blackholeMedia;

	/**
	 * Default constructor
	 */
	public Queue() {

	}

	/**
	 * Copy constructor.
	 * 
	 * @param that Queue object to be copied
	 */
	public Queue(Queue that) {
		this.id = that.id;
		this.period = that.period;
		this.rate = that.rate;
		this.limit = that.limit;
		this.ringDuration = that.ringDuration;
		this.mediaServer = that.mediaServer;
		this.blackholeMedia = that.blackholeMedia;
	}

	/**
	 * Get timer period in milliseconds.
	 * 
	 * @return the period
	 */
	public Integer getPeriod() {
		return period;
	}

	/**
	 * @return the id
	 */
	public String getId() {
		return id;
	}

	/**
	 * @param id the id to set
	 * @return this
	 */
	public Queue setId(String id) {
		this.id = id;
		return this;
	}

	/**
	 * @return the blackholeMedia
	 */
	public Boolean getBlackholeMedia() {
		return blackholeMedia;
	}

	/**
	 * @param blackholeMedia the blackholeMedia to set
	 * @return this
	 */
	public Queue setBlackholeMedia(Boolean blackholeMedia) {
		this.blackholeMedia = blackholeMedia;
		return this;
	}

	/**
	 * Set timer period in milliseconds.
	 * 
	 * @param period the period to set
	 * @return this
	 */
	public Queue setPeriod(Integer period) {
		this.period = period;
		return this;
	}

	/**
	 * Get allowed calls per period.
	 * 
	 * @return the rate
	 */
	public Integer getRate() {
		return rate;
	}

	/**
	 * Set allowed calls per period.
	 * 
	 * @param rate the rate to set
	 * @return this
	 */
	public Queue setRate(Integer rate) {
		this.rate = rate;
		return this;
	}

	/**
	 * Maximum calls allowed in queue. Zero (or negative) for unlimited.
	 * 
	 * @return the limit
	 */
	public Integer getLimit() {
		return limit;
	}

	/**
	 * Maximum calls allowed in queue. Zero (or negative) for unlimited.
	 * 
	 * @param limit the limit to set
	 * @return this
	 */
	public Queue setLimit(Integer limit) {
		this.limit = limit;
		return this;
	}

	/**
	 * Maximum time spent ringing in seconds. Zero (or negative) for no ring.
	 * 
	 * @return the ringDuration
	 */
	public Integer getRingDuration() {
		return ringDuration;
	}

	/**
	 * Maximum time spent ringing in seconds. Zero (or negative) for no ring.
	 * 
	 * @param ringDuration the ringDuration to set
	 * @return this
	 */
	public Queue setRingDuration(Integer ringDuration) {
		this.ringDuration = ringDuration;
		return this;
	}

	/**
	 * Get SIP URI for media server after ringing.
	 * 
	 * @return the mediaServer
	 */
	public URI getMediaServer() {
		return mediaServer;
	}

	/**
	 * Set SIP URI for media server after ringing.
	 * 
	 * @param mediaServer the mediaServer to set
	 * @return this
	 */
	public Queue setMediaServer(URI mediaServer) {
		this.mediaServer = mediaServer;
		return this;
	}

	/**
	 * Instead of media, place call on mute?
	 * 
	 * @return the blackhole
	 */
	public Boolean getBlackhole() {
		return blackholeMedia;
	}

	/**
	 * Instead of media, place call on mute?
	 * 
	 * @param blackhole the blackhole to set
	 * @return this
	 */
	public Queue setBlackhole(Boolean blackhole) {
		this.blackholeMedia = blackholeMedia;
		return this;
	}

}
