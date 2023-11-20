package org.vorpal.blade.services.queue.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
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

	private int intervalHigh = 0;
	private int intervalLow = 0;
	private int intervalMean = 0;

	private String minuteTimer;
	private int minuteHigh = 0;
	private int minuteLow = 0;
	private long minuteMean = 0;

	private String hourlyTimer;
	private int hourlyHigh = 0;
	private int hourlyLow = 0;
	private long hourlyMean = 0;

	private String dailyTimer;
	private int dailyHigh = 0;
	private int dailyLow = 0;
	private int dailyMean = 0;

	Statistics(Queue settings) {
		this.settings = settings;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// not imlemented by design
	}

	public void startTimers() throws ServletException, IOException {

		if (appSession == null) {
			this.appSession = sipUtil.getApplicationSessionByKey(this.settings.getId(), true);

			// minute timer
			this.schedulePeriodicTimer(appSession, 60, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
					int queueSize = queue.getCallflows().size();

					this.minuteHigh = Math.max(minuteHigh, queueSize);
					this.minuteLow = Math.min(minuteLow, queueSize);
					this.minuteMean = (minuteMean + queueSize) / 2;

					sipLogger.info(appSession, "statistics minute report: queue=" + settings.getId() + //
							", high: " + intervalHigh + //
							", low: " + intervalLow + //
							", mean: " + intervalMean);

					// reset interval statistics
					this.intervalHigh = 0;
					this.intervalLow = 0;
					this.dailyMean = 0;
				}

			});

			// hourly timer
			this.schedulePeriodicTimer(appSession, 60 * 60, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
					int queueSize = queue.getCallflows().size();

					this.hourlyHigh = Math.max(hourlyHigh, queueSize);
					this.hourlyLow = Math.min(hourlyLow, queueSize);
					this.hourlyMean = (minuteMean + queueSize) / 2;

					sipLogger.info(appSession, "statistics hourly report: queue=" + settings.getId() + //
							", high: " + minuteHigh + //
							", low: " + minuteLow + //
							", mean: " + minuteMean);

					// reset interval statistics
					this.minuteHigh = 0;
					this.minuteLow = 0;
					this.minuteMean = 0;
				}
			});

			// daily timer
			this.schedulePeriodicTimer(appSession, 60 * 60 * 24, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
					int queueSize = queue.getCallflows().size();

					this.dailyHigh = Math.max(dailyHigh, queueSize);
					this.dailyLow = Math.min(dailyLow, queueSize);
					this.dailyMean = (dailyMean + queueSize) / 2;

					sipLogger.info(appSession, "statistics daily report: queue=" + settings.getId() + //
							", high: " + hourlyHigh + //
							", low: " + hourlyLow + //
							", mean: " + hourlyMean);

					// reset interval statistics
					this.hourlyHigh = 0;
					this.hourlyLow = 0;
					this.hourlyMean = 0;
				}
			});

			// weekly timer
			this.schedulePeriodicTimer(appSession, 60 * 60 * 24 * 7, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(settings.getId());
				if (queue == null) { // Config changed, queue no longer exists
					this.stopTimers();
				} else {
					int queueSize = queue.getCallflows().size();

					sipLogger.info(appSession, "statistics weekly report: queue=" + settings.getId() + //
							", high: " + dailyHigh + //
							", low: " + dailyLow + //
							", mean: " + dailyMean);

					// reset interval statistics
					this.dailyHigh = 0;
					this.dailyLow = 0;
					this.dailyMean = 0;
				}
			});

		}

	}

	public void stopTimers() {

	}

}
