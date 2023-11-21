package org.vorpal.blade.services.queue;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.b2bua.InitialInvite;
import org.vorpal.blade.framework.callflow.Callflow;

/**
 * This class defines a named queue and sets a periodic timer for it to be
 * polled for work.
 */
public class QueueTimer extends Callflow {
	private static final long serialVersionUID = 1L;
	private String name;
	private long period;
	private int rate;
	private String timerId;
	private SipApplicationSession appSession;
//	private CallflowQueue queue;

	/**
	 * This constructor creates a named queue and sets a periodic timer for it to be
	 * polled.
	 * 
	 * @param name   the name of the queue as defined in the configuration
	 * @param period how frequently the queue should be polled in Milliseconds
	 * @param rate   number calls to process
	 * @throws ServletException
	 * @throws IOException
	 */
	public QueueTimer(String name, long period, int rate) throws ServletException, IOException {
		this.name = name;
		this.period = period;
		this.rate = rate;

//		queue = QueueSettingsManager.getCallflowQueue(name);

//		sipLogger.warning("QueueTimer() name=" + name + ", queue: " + queue);

		appSession = sipFactory.createApplicationSession();
		appSession.setExpires(0);
//		startTimer();
	}

	/**
	 * Returns a pseudorandom long value between zero (inclusive) and the specified
	 * bound (exclusive).
	 * 
	 * @param high the specified bound
	 * @return random number
	 */
	public static long RANDOM(long high) {
		return ThreadLocalRandom.current().nextLong(high);
	}

	public QueueTimer startTimer() throws ServletException, IOException {

		if (this.timerId == null) {

			// Create a periodic timer that checks the queue for any work to be done.
			this.timerId = Callflow.startTimer(appSession, RANDOM(period), period, true, false, (timer) -> {

				CallflowQueue queue = QueueSettingsManager.getQueue(name);
				ConcurrentLinkedDeque<Callflow> deque = queue.getCallflows();

				queue.intervalHigh = deque.size();

				queue.minuteHigh = Math.max(queue.minuteHigh, queue.intervalHigh);

				Callflow callflow;
				for (int i = 0; i < rate; i++) {
					callflow = queue.getCallflows().pollLast();
					if (callflow != null) {
						sipLogger.fine(((InitialInvite) callflow).getOutboundRequest(), "Continuing callflow... ");
						callflow.processContinue();
					} else {
						break;
					}
				}

//				if (deque.size() == 0) {
//					timer.cancel();
//				}

				queue.intervalLow = deque.size();
				queue.minuteLow = Math.min(queue.minuteLow, queue.intervalLow);

//				queue.intervalMean = (queue.intervalHigh
//						- queue.intervalLow) / 2;

			});

		}

		return this;
	}

	/**
	 * Stops the timer from processing messages in the queue.
	 * 
	 * @return this
	 */
	public QueueTimer stopTimer() {
		if (timerId != null) {
			stopTimer(appSession, timerId);
			timerId = null;
		}

		return this;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// not implemented;
	}

	public String getName() {
		return name;
	}

	public QueueTimer setName(String name) {
		this.name = name;
		return this;
	}

	public long getPeriod() {
		return period;
	}

	public QueueTimer setPeriod(long period) {
		this.period = period;
		return this;
	}

	public int getRate() {
		return rate;
	}

	public QueueTimer setRate(int rate) {
		this.rate = rate;
		return this;
	}

	public String getTimerId() {
		return timerId;
	}

	public QueueTimer setTimerId(String timerId) {
		this.timerId = timerId;
		return this;
	}

	public SipApplicationSession getAppSession() {
		return appSession;
	}

	public QueueTimer setAppSession(SipApplicationSession appSession) {
		this.appSession = appSession;
		return this;
	}

}
