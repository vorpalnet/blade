package org.vorpal.blade.services.queue;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.services.queue.callflows.Statistics;

public class CallflowQueue {
	private ConcurrentLinkedDeque<Callflow> callflows;

	private QueueTimer timer;
	private Queue settings;

	private SipApplicationSession appSession;
	private String timerId;
	
	private Statistics statistics=null;
	

	/**
	 * Constructs a new object from the Queue parameters in the config file.
	 * 
	 * @param settings
	 * @throws ServletException
	 * @throws IOException
	 */
	public CallflowQueue(Queue settings) throws ServletException, IOException {
		callflows = new ConcurrentLinkedDeque<>();
	}

//	public CallflowQueue startTimer() {
//		return this;
//	}
//
//	public CallflowQueue stopTimer() {
//		return null;
//	}

	/**
	 * This method is called by the constructor, but it can also be called to
	 * reinitialize the queue timers when the configuration has changed.
	 * 
	 * @param settings
	 * @return this
	 * @throws ServletException
	 * @throws IOException
	 */
	public CallflowQueue initialize(Queue settings) throws ServletException, IOException {

		// copy constructor because the config could change
		this.settings = new Queue(settings);

		// If a timer exists, kill it.
		if (timer != null) {
			timer.stopTimer();
		}

		// Start a new timer
		timer = new QueueTimer(settings.getId(), settings.getPeriod(), settings.getRate());
		timer.startTimer();

		return this;
	}

	/**
	 * @return the callflows
	 */
	public ConcurrentLinkedDeque<Callflow> getCallflows() {
		return callflows;
	}

	/**
	 * @param callflows the callflows to set
	 * @return this
	 */
	public CallflowQueue setCallflows(ConcurrentLinkedDeque<Callflow> callflows) {
		this.callflows = callflows;
		return this;
	}

	/**
	 * @return the settings
	 */
	public Queue getSettings() {
		return settings;
	}

	/**
	 * @param settings the settings to set
	 * @return this
	 */
	public CallflowQueue setSettings(Queue settings) {
		this.settings = settings;
		return this;

	}

	/**
	 * @return the appSession
	 */
	public SipApplicationSession getAppSession() {
		return appSession;
	}

	/**
	 * @param appSession the appSession to set
	 * @return this
	 */
	public CallflowQueue setAppSession(SipApplicationSession appSession) {
		this.appSession = appSession;
		return this;

	}

	/**
	 * @return the timerId
	 */
	public String getTimerId() {
		return timerId;
	}

	/**
	 * @param timerId the timerId to set
	 * @return this
	 */
	public CallflowQueue setTimerId(String timerId) {
		this.timerId = timerId;
		return this;

	}

}
