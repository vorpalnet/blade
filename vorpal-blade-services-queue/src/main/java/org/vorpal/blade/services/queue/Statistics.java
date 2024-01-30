package org.vorpal.blade.services.queue;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadLocalRandom;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.logging.Logger;

public class Statistics {

//	public String id;
	public Queue queue;

	public String minuteTimer;
	public int minuteHigh = 0;
	public int minuteLow = 0;

	public String hourlyTimer;
	public int hourlyHigh = 0;
	public int hourlyLow = 0;

	public String dailyTimer;
	public int dailyHigh = 0;
	public int dailyLow = 0;

	private static Logger sipLogger;

	private Timer minute, hourly, daily;

	public Statistics(Queue queue) {

		if (sipLogger == null) {
			sipLogger = AsyncSipServlet.getSipLogger();
		}

		this.queue = queue;

		minute = new Timer();

		// jwm - disabled for testing
		minute.schedule(minuteTask, RANDOM(60000), 1000 * 60);

		hourly = new Timer();

		// jwm - disabled for testing
		hourly.schedule(hourlyTask, RANDOM(60000), 1000 * 60 * 60);

		daily = new Timer();

		// jwm - disabled for testing
		daily.schedule(dailyTask, RANDOM(60000), 1000 * 60 * 60 * 24);
	}

	public void stopTimers() {
		// jwm - disabled for testing
		minute.cancel();
		hourly.cancel();
		daily.cancel();
	}

	public static long RANDOM(long high) {
		return ThreadLocalRandom.current().nextLong(high);
	}

	public void intervalTask() {
		int size = queue.callflows.size();

		minuteLow = Math.min(size, minuteLow);
		minuteHigh = Math.max(size, minuteHigh);
	}

	public TimerTask minuteTask = new TimerTask() {
		public void run() {

			hourlyLow = Math.min(minuteLow, hourlyLow);
			hourlyHigh = Math.max(minuteHigh, hourlyHigh);

			if (minuteHigh > 0) {
				sipLogger.info("minute report:\t queue=" + queue.id + ", low=" + minuteLow + ", high=" + minuteHigh);
			}

			minuteLow = 0;
			minuteHigh = 0;

		}
	};

	public TimerTask hourlyTask = new TimerTask() {
		public void run() {
			dailyLow = Math.min(hourlyLow, dailyLow);
			dailyHigh = Math.max(hourlyHigh, dailyHigh);

			if (hourlyHigh > 0) {
				sipLogger.info("hourly report: queue=" + queue.id + ", low=" + hourlyLow + ", high=" + hourlyHigh);
			}

			hourlyLow = 0;
			hourlyHigh = 0;
		}
	};

	public TimerTask dailyTask = new TimerTask() {
		public void run() {

			if (dailyHigh > 0) {
				sipLogger.info("daily  report: queue=" + queue.id + ", low=" + dailyLow + ", high=" + dailyHigh);
			}

			dailyLow = 0;
			dailyHigh = 0;
		}
	};

}
