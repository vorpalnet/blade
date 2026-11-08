package org.vorpal.blade.services.presence;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v3.configuration.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

@SchemaAbout(
		name = "Presence",
		tagline = "Agent presence and skill routing",
		description = "Keeps each agent's Ready or Not Ready state and skills from SIP PUBLISH, tells "
				+ "watchers with NOTIFY, and routes a call to a skill's address to the agent Ready for that "
				+ "skill who has been idle longest, holding it with 182 Queued while nobody is free. Skills "
				+ "are whatever agents publish; nothing here names them.")
public class PresenceSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private int noAnswerSeconds = 15;
	private int queuedRefreshSeconds = 60;
	private int maxExpiresSeconds = 3600;
	private int maxWaitSeconds = 600;

	@JsonPropertyDescription("Seconds an offered call rings an agent before presence takes it back, sets "
			+ "the agent Not Ready and offers the call to the next agent.")
	public int getNoAnswerSeconds() {
		return noAnswerSeconds;
	}

	public void setNoAnswerSeconds(int noAnswerSeconds) {
		this.noAnswerSeconds = noAnswerSeconds;
	}

	@JsonPropertyDescription("Seconds between the 182 Queued responses that keep a waiting call's INVITE "
			+ "alive. Keep it well under three minutes, the proxies' Timer C.")
	public int getQueuedRefreshSeconds() {
		return queuedRefreshSeconds;
	}

	public void setQueuedRefreshSeconds(int queuedRefreshSeconds) {
		this.queuedRefreshSeconds = queuedRefreshSeconds;
	}

	@JsonPropertyDescription("Seconds a call may wait for an agent before presence answers 480 and takes "
			+ "it out of line; 0 waits for as long as the caller does. Also ends calls whose caller "
			+ "vanished without hanging up.")
	public int getMaxWaitSeconds() {
		return maxWaitSeconds;
	}

	public void setMaxWaitSeconds(int maxWaitSeconds) {
		this.maxWaitSeconds = maxWaitSeconds;
	}

	@JsonPropertyDescription("The longest publication or subscription presence grants, in seconds. A "
			+ "longer Expires is lowered to this.")
	public int getMaxExpiresSeconds() {
		return maxExpiresSeconds;
	}

	public void setMaxExpiresSeconds(int maxExpiresSeconds) {
		this.maxExpiresSeconds = maxExpiresSeconds;
	}
}
