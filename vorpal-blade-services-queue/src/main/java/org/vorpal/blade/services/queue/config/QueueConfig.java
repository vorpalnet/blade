package org.vorpal.blade.services.queue.config;

import java.util.LinkedList;

import org.vorpal.blade.framework.config.RouterConfig;
import org.vorpal.blade.services.queue.Queue;

/**
 * Extends RouterConfig to provide a Selector to identify which queue (if any)
 * is used in processing the call.
 */
public class QueueConfig extends RouterConfig {
	private static final long serialVersionUID = 1L;
	private LinkedList<Queue> queues = new LinkedList<>();

	/**
	 * @return the queues
	 */
	public LinkedList<Queue> getQueues() {
		return queues;
	}

	/**
	 * @param queues the queues to set
	 * @return this
	 */
	public QueueConfig setQueues(LinkedList<Queue> queues) {
		this.queues = queues;
		return this;
	}

	/**
	 * @param queue
	 * @return this
	 */
	public QueueConfig addQueue(Queue queue) {
		queues.add(queue);
		return this;
	}

}
