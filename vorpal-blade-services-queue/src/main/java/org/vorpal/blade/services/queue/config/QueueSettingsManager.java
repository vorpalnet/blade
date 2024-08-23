package org.vorpal.blade.services.queue.config;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.services.queue.Queue;
import org.vorpal.blade.services.queue.QueueServlet;

/**
 * 
 */
public class QueueSettingsManager extends SettingsManager<QueueConfig> {

	// jwm-testing
//	public static HashMap<String, QueueCallflow> queues = new HashMap<>();

	// private static HashMap<String, QueueCallflow> queues = new HashMap<>();
	// private HashMap<String, QueueCallflow> queues = new HashMap<>();

	/**
	 * Create a custom SettingsManager with a sample config file.
	 * 
	 * @param event
	 * @param sample
	 */
	public QueueSettingsManager(SipServletContextEvent event, QueueConfig sample) throws ServletException, IOException{
		super(event, QueueConfig.class, sample);
	}

//	/**
//	 * Returns a named queue.
//	 * 
//	 * @param id name of the queue
//	 * @return the queue
//	 */
//	public static QueueCallflow getQueue(String id) {
//		QueueCallflow queueCallflow = null;
//
//		queueCallflow = queues.get(id);
//
//		return queueCallflow;
//	}

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

		sipLogger.fine("QueueSettingsManager.initialize...");

		try {
			QueueAttributes qa;
			Queue queue;

			for (String queueName : config.getQueues().keySet()) {
				qa = config.getQueues().get(queueName);
				sipLogger.fine("QueueSettingsManager.initialize, loading attributes for queue=" + queueName);
				queue = QueueServlet.queues.get(queueName);
				if (queue == null) {
					sipLogger.fine("QueueSettingsManager.initialize, creating new queue=" + queueName);
					queue = new Queue(queueName);
					QueueServlet.queues.put(queueName, queue);
				} else {
					sipLogger.fine("QueueSettingsManager.initialize, reinitializing old queue=" + queueName);
				}

				queue.initialize(qa);

			}

		} catch (Exception e) {
//			if (sipLogger != null) {
			sipLogger.severe("Queue application failed to initialize. Check config file.");
			sipLogger.severe(e);
//			} else {
//				System.out.println("Queue application failed to initialize. Check config file.");
//				e.printStackTrace();
//			}
		}

	}

}
