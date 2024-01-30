package org.vorpal.blade.services.queue;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.services.queue.config.QueueAttributes;

public class Queue {
	public String id;
	public Statistics statistics;
	public QueueAttributes attributes;
	public Timer timer;
	public Logger sipLogger;

	public ConcurrentLinkedDeque<QueueCallflow> callflows;

	public Queue(String id) {
		this.id = id;
		sipLogger = SettingsManager.getSipLogger();
		statistics = new Statistics(this);
		callflows = new ConcurrentLinkedDeque<>();
	}

	public void initialize(QueueAttributes attributes) {
		this.attributes = attributes;

		if (timer != null) {
			timer.cancel();
		}

		timer = new Timer(id);

		// jwm - testing timers
		timer.schedule(queueTask, attributes.period, attributes.period);
		// timer.schedule(queueTask, attributes.period);

	}

	public void stopTimers() {
		timer.cancel();
		statistics.stopTimers();
	}

	public TimerTask queueTask = new TimerTask() {

		public void run() {

//			sipLogger.fine("timer fired... queue=" + id + ", count=" + callflows.size());

			QueueCallflow callflow;
			for (int i = 0; i < attributes.rate; i++) {
				callflow = callflows.pollLast();
				if (callflow != null) {
//					SettingsManager.sipLogger.finer("Continuing callflow... ");

					try {
						callflow.complete();
					} catch (Exception e) {
						sipLogger.severe(e);
					}

				} else {
					break;
				}
			}

			// jmw - testing timers
			// timer.schedule(queueTask, attributes.period);

		}
	};

}
