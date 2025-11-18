package org.vorpal.blade.services.queue;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
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

		this.attributes = attributes;

		if (sipLogger.isLoggable(Level.FINE)) {
			sipLogger.fine("Queue.initialize - id=" + id + //
					", period=" + attributes.getPeriod() + //
					", rate" + attributes.getRate() + //
					", ringDuration=" + attributes.getRingDuration() + //
					", ringPeriod=" + attributes.getRingPeriod() + //
					", announcement=" + attributes.getAnnouncement());
		}
		if (timer != null) {

			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine("Queue.initialize - canceling old timer...");
			}

			timer.cancel();
		}

		// jwm-testing
		// timer = new Timer(id);
		timer = new Timer(); // timer id gives problems?
		queueTask = new TimerTask() {
			public void run() {
				if (sipLogger.isLoggable(Level.FINE)) {
					sipLogger.fine("Queue.initialize - timer fired, queue=" + id + ", count=" + callflows.size());
				} // jwm testing, does this grow? no.
					// QueueMemHog memHog = new QueueMemHog();
				QueueCallflow callflow;

				// for (int i = 0; i < attributes.rate; i++) {
				int i = 0;

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(
							"Queue.initialize - queueTask beginning do loop... callflows.size=" + callflows.size());
				}
				do {
					callflow = callflows.pollLast();

					if (callflow != null) {
						if (sipLogger.isLoggable(Level.FINER)) {
							sipLogger.finer(callflow.aliceRequest,
									"Queue.initialize - queueTask, continuing callflow, state="
											+ callflow.getState().toString());
						}
						try {
// jwm - why?
//							if (QueueState.RINGING == callflow.getState()) {
//								sipLogger.fine("Queue.initialize - queueTask, continuing callflow, state="
//										+ callflow.getState().toString());
//
//								i++;
//							}

							i++;
							callflow.complete();

						} catch (Exception e) {
							sipLogger.severe(callflow.aliceRequest, "Queue.initialize - queueTask, caught Exception "
									+ e.getClass().getName() + " " + e.getMessage());
							sipLogger.logStackTrace(callflow.aliceRequest, e);
						}
					} else {

						sipLogger
								.finer("Queue.initialize - queueTask, queue empty, callflows.size=" + callflows.size());

						break;
					}

					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer("Queue.initialize - queueTask, i=" + i + ", attributes.rate=" + attributes.rate
								+ "callflows." + ", callflows.size=" + callflows.size());
					}
				} while (i < attributes.rate);

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer("Queue.initialize - queueTask, do loop ended. callflows.size=" + callflows.size());
				}
				callflow = null;
			}
		};

		// jwm - testing timers
		if (sipLogger.isLoggable(Level.FINE)) {
			sipLogger.fine("Queue.initialize, creating new timer...");
		}
//		timer.schedule(queueTask, attributes.period, attributes.period);
		timer.scheduleAtFixedRate(queueTask, attributes.period, attributes.period);

	}

	public void stopTimers() {
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.fine("Queue.stopTimers...");
		}

		timer.cancel();
		statistics.stopTimers();
	}

	// For memleak testing... No memleak found.
	static class QueueMemHog {
		byte[] mb1 = new byte[1024 * 1024];
	}

}
