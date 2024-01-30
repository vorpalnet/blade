package org.vorpal.blade.services.queue.config;

import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.config.RouterConfig;

/**
 * Defines how the Queue application is configured.
 */
public class QueueConfig extends RouterConfig {
	private static final long serialVersionUID = 1L;
	public Map<String, QueueAttributes> queues = new HashMap<>();

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

}
