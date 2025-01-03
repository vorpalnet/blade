package org.vorpal.blade.services.queue;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.services.queue.QueueCallflow.QueueState;
import org.vorpal.blade.services.queue.config.QueueAttributes;

public class Queue {
	public String id;
	public Statistics statistics;
	public QueueAttributes attributes;
	public Timer timer;
	public Logger sipLogger;
	public ConcurrentLinkedDeque<QueueCallflow> callflows;

	public TimerTask queueTask;

	public Queue(String id) {

		// sipLogger not initialized yet
		// sipLogger.fine("Queue<init>, creating new queue=" + id);

		this.id = id;
		sipLogger = SettingsManager.getSipLogger();
		statistics = new Statistics(this); // statistics never change
		callflows = new ConcurrentLinkedDeque<>();
	}

	public void initialize(QueueAttributes attributes) {

		sipLogger.fine("Queue.initialize, period=" + attributes.getPeriod() + ", rate=" + attributes.getRate());

		this.attributes = attributes;

		if (timer != null) {
			sipLogger.fine("Queue.initialize, canceling old timer...");

			timer.cancel();
		}

		// jwm-testing
		// timer = new Timer(id);
		timer = new Timer(); // timer id gives problems?
		queueTask = new TimerTask() {
			public void run() {
				// sipLogger.fine("timer fired... queue=" + id + ", count=" + callflows.size());
				// jwm testing, does this grow? no.
				// QueueMemHog memHog = new QueueMemHog();
				QueueCallflow callflow;

				// for (int i = 0; i < attributes.rate; i++) {
				int i = 0;
				do {
					callflow = callflows.pollLast();
					if (callflow != null) {
						SettingsManager.sipLogger
								.fine("Continuing callflow... state=" + callflow.getState().toString());

						try {

							if (QueueState.RINGING == callflow.getState()) {
								i++;
							}

							callflow.complete();

						} catch (Exception e) {
							sipLogger.severe(e);
						}
					} else {
						break;
					}
				} while (i < attributes.rate);

				callflow = null;
			}
		};

		// jwm - testing timers
		sipLogger.fine("Queue.initialize, creating new timer...");
//		timer.schedule(queueTask, attributes.period, attributes.period);
		timer.scheduleAtFixedRate(queueTask, attributes.period, attributes.period);

	}

	public void stopTimers() {
		sipLogger.fine("Queue.stopTimers...");

		timer.cancel();
		statistics.stopTimers();
	}

	// For memleak testing... No memleak found.
	static class QueueMemHog {
		byte[] mb1 = new byte[1024 * 1024];
	}

}
