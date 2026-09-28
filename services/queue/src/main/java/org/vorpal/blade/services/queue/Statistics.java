package org.vorpal.blade.services.queue;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadLocalRandom;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

/// A queue's length over the last minute, hour and day: the low and the high,
/// logged, and the minute published as [BladeEventTypes#QUEUE_DEPTH].
///
/// `QueueServlet` samples the length on its interval ([#intervalTask]); each
/// minute folds into the hour and each hour into the day.
public class Statistics {

	/// The lowest and highest queue length seen over one period.
	static final class Watermark {

		/// Not yet observed. The lows used to start and reset at 0, and
		/// `Math.min(size, 0)` is 0 whatever the queue did, so every report
		/// said the queue had emptied.
		private static final int NONE = Integer.MAX_VALUE;

		private int low = NONE;
		private int high = 0;

		void observe(int size) {
			low = Math.min(size, low);
			high = Math.max(size, high);
		}

		/// Take in a finer period that has just ended.
		void fold(Watermark finer) {
			low = Math.min(finer.low, low);
			high = Math.max(finer.high, high);
		}

		/// Whether the queue held anybody during the period.
		boolean occupied() {
			return high > 0;
		}

		int low() {
			return (low == NONE) ? 0 : low;
		}

		int high() {
			return high;
		}

		void reset() {
			low = NONE;
			high = 0;
		}
	}

	public Queue queue;

	final Watermark minute = new Watermark();
	final Watermark hour = new Watermark();
	final Watermark day = new Watermark();

	private static Logger sipLogger;

	/// One set per queue. These were static, so each new queue replaced the
	/// previous queue's timers and [#stopTimers] cancelled only the last
	/// queue's; the others kept running past an undeploy.
	private final Timer minuteTimer;
	private final Timer hourlyTimer;
	private final Timer dailyTimer;

	public Statistics(Queue queue) {

		if (sipLogger == null) {
			sipLogger = AsyncSipServlet.getSipLogger();
		}

		this.queue = queue;

		minuteTimer = new Timer("queue-" + queue.id + "-minute", true);
		minuteTimer.schedule(minuteTask, RANDOM(60000), 1000 * 60);

		hourlyTimer = new Timer("queue-" + queue.id + "-hourly", true);
		hourlyTimer.schedule(hourlyTask, RANDOM(60000), 1000 * 60 * 60);

		dailyTimer = new Timer("queue-" + queue.id + "-daily", true);
		dailyTimer.schedule(dailyTask, RANDOM(60000), 1000 * 60 * 60 * 24);
	}

	public void stopTimers() {
		sipLogger.fine("Statistics.stopTimers...");
		minuteTimer.cancel();
		hourlyTimer.cancel();
		dailyTimer.cancel();
	}

	public static long RANDOM(long high) {
		return ThreadLocalRandom.current().nextLong(high);
	}

	public void intervalTask() {
		minute.observe(queue.callflows.size());
	}

	public TimerTask minuteTask = new TimerTask() {
		public void run() {
			hour.fold(minute);
			if (minute.occupied()) {
				int low = minute.low();
				int high = minute.high();
				int depth = queue.callflows.size();
				sipLogger.info("minute report:\t queue=" + queue.id + ", low=" + low + ", high=" + high);
				Events.publish(BladeEventTypes.QUEUE_DEPTH, queue.id, data -> data
						.put("queue", queue.id).put("low", low).put("high", high).put("depth", depth)
						.put("node", SettingsManager.getServerName()));
			}
			minute.reset();
		}
	};

	public TimerTask hourlyTask = new TimerTask() {
		public void run() {
			day.fold(hour);
			if (hour.occupied()) {
				sipLogger.info("hourly report: queue=" + queue.id + ", low=" + hour.low() + ", high=" + hour.high());
			}
			hour.reset();
		}
	};

	public TimerTask dailyTask = new TimerTask() {
		public void run() {
			if (day.occupied()) {
				sipLogger.info("daily  report: queue=" + queue.id + ", low=" + day.low() + ", high=" + day.high());
			}
			day.reset();
		}
	};

}
