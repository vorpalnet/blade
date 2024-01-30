package org.vorpal.blade.services.queue.config;

import java.io.IOException;
import java.util.HashMap;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.services.queue.Queue;
import org.vorpal.blade.services.queue.QueueCallflow;
import org.vorpal.blade.services.queue.QueueServlet;

/**
 * 
 */
public class QueueSettingsManager extends SettingsManager<QueueConfig> {

	private static HashMap<String, QueueCallflow> queues = new HashMap<>();

	/**
	 * Create a custom SettingsManager with a sample config file.
	 * 
	 * @param event
	 * @param sample
	 */
	public QueueSettingsManager(SipServletContextEvent event, QueueConfig sample) {
		super(event, QueueConfig.class, sample);
	}

	/**
	 * Returns a named queue.
	 * 
	 * @param id name of the queue
	 * @return the queue
	 */
	public static QueueCallflow getQueue(String id) {
		return queues.get(id);
	}

	/**
	 * This is an overloaded method invoked by the base class 'SettingsManager'. It
	 * creates instances of ConcurrentLinkedDeque to be used as the actual FIFO
	 * queues. Since this method may be called multiple times, care is taken not to
	 * clobber any existing queues.
	 * 
	 * @param config
	 */
	@Override
	public void initialize(QueueConfig config) {

		try {
			QueueAttributes qa;
			Queue queue;

			for (String queueName : config.getQueues().keySet()) {
				qa = config.getQueues().get(queueName);
				queue = QueueServlet.queues.get(queueName);
				if (queue == null) {
					queue = new Queue(queueName);
					QueueServlet.queues.put(queueName, queue);
				}
				queue.initialize(qa);
			}

		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.severe("Queue application failed to initialize. Check config file.");
				sipLogger.severe(e);
			} else {
				System.out.println("Queue application failed to initialize. Check config file.");
				e.printStackTrace();
			}
		}

	}

}
