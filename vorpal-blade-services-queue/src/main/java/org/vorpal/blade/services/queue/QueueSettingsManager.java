package org.vorpal.blade.services.queue;

import java.io.IOException;
import java.util.HashMap;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.SettingsManager;

/**
 * 
 */
public class QueueSettingsManager extends SettingsManager<QueueConfig> {

//	private static ConcurrentHashMap<String, CallflowQueue> queues = new ConcurrentHashMap<>();
	private static HashMap<String, CallflowQueue> queues = new HashMap<>();

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
	public static CallflowQueue getQueue(String id) {

		return queues.get(id);

//		CallflowQueue queue = queues.get(id);
//
//		sipLogger.severe("QueueSettingsManager.getQueue... id:" + id + ", queue: " + queue);
//		if (queues != null) {
//			sipLogger.severe("QueueSettingsManager.getQueue... queues.size:" + queues.size());
//		}
//
//		return queue;
	}

	/**
	 * This is an overloaded method invoked by the base class 'SettingsManager'. It
	 * creates instances of ConcurrentLinkedDeque to be used as the actual FIFO
	 * queues. Since this method may be called multiple times, care is taken not to
	 * clobber any existing queues.
	 * 
	 * @param config
	 * @throws IOException
	 * @throws ServletException
	 */
	@Override
	public void initialize(QueueConfig config) {

		try {

//			if (queues == null) {
//				sipLogger.severe("Instantiating the 'queues'...");
//				queues = new ConcurrentHashMap<>();
//			}

			CallflowQueue callflowQueue;

			for (Queue settings : config.getQueues()) {

				sipLogger.severe("Queue Settings...");
				sipLogger.severe("id: " + settings.getId());
				sipLogger.severe("limit: " + settings.getLimit());
				sipLogger.severe("period: " + settings.getPeriod());
				sipLogger.severe("rate: " + settings.getRate());
				sipLogger.severe("duration: " + settings.getRingDuration());
				sipLogger.severe("media: " + settings.getBlackholeMedia());

				callflowQueue = this.getQueue(settings.getId());
				if (callflowQueue == null) {
					callflowQueue = new CallflowQueue(settings);

					sipLogger.warning("I'm putting a callflowQueue in queues: " + callflowQueue);
					this.queues.put(settings.getId(), callflowQueue);
					sipLogger.warning("I'm getting a callflowQueue in queues: " + this.queues.get(settings.getId()));

					callflowQueue.initialize(settings);

				} else {
					callflowQueue.initialize(settings);
				}

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
