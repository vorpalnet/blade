package org.vorpal.blade.services.queue.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.RouterConfig;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/**
 * Defines how the Queue application is configured.
 */
@SchemaAbout(
		name = "Queue",
		tagline = "Call Queueing",
		description = "Holds inbound calls in a managed queue when no downstream resource is "
				+ "available, releasing them to a destination as capacity frees up. B2BUA-based, "
				+ "so it stays in the dialog for the life of the queued call.")
public class QueueConfig extends RouterConfig {
	private static final long serialVersionUID = 1L;
	
	@JsonPropertyDescription("Map of named queues and their configuration attributes.")
	public Map<String, QueueAttributes> queues = new HashMap<>();

	private String driverName;
	private Map<String, String> driverProperties = new LinkedHashMap<>();

	public QueueConfig() {
		// default constructor
	}

	/**
	 * @return the queues
	 */
	public Map<String, QueueAttributes> getQueues() {
		return queues;
	}

	/**
	 * @param queues the queues to set
	 * @return this
	 */
	public QueueConfig setQueues(Map<String, QueueAttributes> queues) {
		this.queues = queues;
		return this;
	}

	/**
	 * @param id
	 * @param queueAttributes
	 * @return this
	 */
	public QueueConfig addQueue(String id, QueueAttributes queueAttributes) {
		queues.put(id, queueAttributes);
		return this;
	}


	@JsonPropertyDescription("The JSR-309 media server driver for queues that play media to waiting callers, by "
			+ "its registered name. Empty uses the only driver the application carries. Used to connect the first "
			+ "time a queue with media loads; changing it later needs a restart.")
	public String getDriverName() {
		return driverName;
	}

	public void setDriverName(String driverName) {
		this.driverName = driverName;
	}

	@JsonPropertyDescription("Driver-specific properties passed verbatim to the JSR-309 driver, such as the media "
			+ "server's address. Read when the driver connects.")
	public Map<String, String> getDriverProperties() {
		return driverProperties;
	}

	public void setDriverProperties(Map<String, String> driverProperties) {
		this.driverProperties = driverProperties;
	}
}
