package org.vorpal.blade.services.queue.callflows;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.services.queue.CallflowQueue;
import org.vorpal.blade.services.queue.Queue;
import org.vorpal.blade.services.queue.QueueSettingsManager;

/**
 * This class logs minute-by-minute, hourly and daily statistics.
 */
public class Statistics extends Callflow {
	private static final long serialVersionUID = 1L;
	private Queue settings;

	private SipApplicationSession appSession;

	public Statistics(Queue settings) {
		this.settings = settings;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// not imlemented by design
	}

	public void startTimers() throws ServletException, IOException {

		if (appSession == null) {
			appSession = sipFactory.createApplicationSession();
			appSession.setExpires(0);

			// minute timer

			startTimer(appSession, RANDOM(60000), 60000, false, false, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
//					int queueSize = queue.getCallflows().size();

					// Zero values may mean the counter was reset

					queue.hourlyHigh = Math.max(queue.hourlyHigh, queue.minuteHigh);
					queue.hourlyLow = Math.min(queue.hourlyLow, queue.minuteLow);

//					this.minuteHigh = Math.max(minuteHigh, intervalHigh);
//					this.minuteLow = Math.min(minuteLow, intervalLow);
//					this.minuteMean = (0 == minuteMean) ? intervalMean : (minuteMean + intervalMean) / 2;

					sipLogger.info(appSession, "statistics minute report: queue=" + settings.getId() + //
							", high: " + queue.minuteHigh + //
							", low: " + queue.minuteLow);

					queue.minuteHigh = 0;
					queue.minuteLow = 0;

//					// reset interval statistics
//					this.intervalHigh = 0;
//					this.intervalLow = 0;
//					this.dailyMean = 0;
				}

			});

			// hourly timer
			startTimer(appSession, RANDOM(60000), 1000 * 60 * 60, false, false, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
					int queueSize = queue.getCallflows().size();

					queue.dailyHigh = Math.max(queue.dailyHigh, queue.hourlyHigh);
					queue.dailyLow = Math.min(queue.dailyLow, queue.hourlyLow);

					sipLogger.info(appSession, "statistics hourly report: queue=" + settings.getId() + //
							", high: " + queue.hourlyHigh + //
							", low: " + queue.hourlyLow);

					// reset interval statistics
					queue.hourlyHigh = 0;
					queue.hourlyLow = 0;
				}
			});

			// daily timer
			startTimer(appSession, RANDOM(60000), 1000 * 60 * 60 * 24, false, false, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
					int queueSize = queue.getCallflows().size();

					queue.weeklyHigh = Math.max(queue.weeklyHigh, queue.dailyHigh);
					queue.weeklyLow = Math.min(queue.weeklyLow, queue.dailyLow);

					sipLogger.info(appSession, "statistics daily report: queue=" + settings.getId() + //
							", high: " + queue.dailyHigh + //
							", low: " + queue.dailyLow);

					// reset interval statistics
					queue.hourlyHigh = 0;
					queue.hourlyLow = 0;
				}
			});

			// weekly timer
			startTimer(appSession, RANDOM(60000), 1000 * 60 * 60 * 24 * 7, false, false, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
					int queueSize = queue.getCallflows().size();

					sipLogger.info(appSession, "statistics weekly report: queue=" + settings.getId() + //
							", high: " + queue.weeklyHigh + //
							", low: " + queue.weeklyLow);

					// reset interval statistics
					queue.weeklyHigh = 0;
					queue.weeklyLow = 0;
				}
			});

		}

	}

	public void stopTimers() {

		if (this.appSession != null) {
			for (ServletTimer timer : appSession.getTimers()) {
				timer.cancel();
			}
			appSession.invalidate();
			appSession = null;
		}

	}

	public static long RANDOM(long high) {
		return ThreadLocalRandom.current().nextLong(high);
	}

}
