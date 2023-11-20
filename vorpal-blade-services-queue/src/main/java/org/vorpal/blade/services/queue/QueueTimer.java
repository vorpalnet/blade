package org.vorpal.blade.services.queue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.callflow.Callflow;

/**
 * This class defines a named queue and sets a periodic timer for it to be
 * polled for work.
 */
public class QueueTimer extends Callflow {
	private static final long serialVersionUID = 1L;
	private String name;
	private long frequency;
	private int rate;
	private String timerId;
	private SipApplicationSession appSession;
//	private CallflowQueue queue;

	/**
	 * This constructor creates a named queue and sets a periodic timer for it to be
	 * polled.
	 * 
	 * @param name                    the name of the queue as defined in the
	 *                                configuration
	 * @param frequencyInMilliseconds how often the queue should be polled
	 * @param rate                    number calls to process
	 * @throws ServletException
	 * @throws IOException
	 */
	public QueueTimer(String name, long frequencyInMilliseconds, int rate) throws ServletException, IOException {
		this.name = name;
		this.frequency = frequencyInMilliseconds;
		this.rate = rate;

//		queue = QueueSettingsManager.getCallflowQueue(name);

//		sipLogger.warning("QueueTimer() name=" + name + ", queue: " + queue);

//		appSession = sipFactory.createApplicationSessionByKey(name);
		appSession = sipUtil.getApplicationSessionByKey(name, true);
//		startTimer();
	}

	public QueueTimer startTimer() throws ServletException, IOException {

		sipLogger.warning("Calling startTimer...");

		// Create a periodic timer that checks the queue for any work to be done.
		timerId = schedulePeriodicTimerInMilliseconds(appSession, frequency, (timer) -> {

//			sipLogger.warning("Periodic timer... " + timer.getId());

			CallflowQueue queue = QueueSettingsManager.getQueue(name);
//			sipLogger.warning("QueueTimer.startTimer()... queue" + queue);

			Callflow callflow;
			for (int i = 0; i < rate; i++) {
				callflow = queue.getCallflows().pollLast();
				if (callflow != null) {

					sipLogger.warning("Continuing callflow... " + callflow);
					callflow.processContinue();
				} else {
//					sipLogger.warning("No callflow in the queue...");
					break;

					// stop the timer here

				}
			}

		});

		return this;
	}

	/**
	 * Stops the timer from processing messages in the queue.
	 * 
	 * @return this
	 */
	public QueueTimer stopTimer() {

		cancelTimer(appSession, timerId);

		return this;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// not implemented;
	}

}
