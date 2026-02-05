package org.vorpal.blade.services.queue;

import java.io.IOException;
import java.util.Collection;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;

import org.vorpal.blade.framework.v2.b2bua.Terminate;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.callflow.Expectation;
import org.vorpal.blade.services.queue.config.QueueAttributes;

public class QueueCallflow extends Callflow {
	private static final long serialVersionUID = 1L;
	public SipServletRequest aliceRequest;
	public SipServletRequest mediaRequest;
	public String ringingPeriodTimer;
	public String queueId;

	public enum QueueState {
		CANCELED, RINGING, MEDIA_RINGING, MEDIA_CONNECTED
	};

	QueueAttributes attributes;

	public QueueCallflow(String queueId, QueueAttributes attributes) {
		this.queueId = queueId;
		this.attributes = attributes;
	}

	@Override
	public void process(SipServletRequest inboundRequest) throws ServletException, IOException {
		SipApplicationSession appSession = inboundRequest.getApplicationSession();
		this.aliceRequest = inboundRequest;

		try {

			Expectation cancelWhileRinging = this.expectRequest(aliceRequest.getSession(), CANCEL, (cancel) -> {

				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(aliceRequest.getSession(),
							"QueueCallflow.process - Expectation cancelWhileRinging invoked...");
				}

				stopTimers();
				setState(QueueState.CANCELED);

				// Try to CANCEL or BYE any outbound requests;
				sipLogger.finer(inboundRequest,
						"QueueCallflow.process.cancelWhileRinging - invoking Cancel.process in case there are outbound requests.");
				Callflow cancelCallflow = new Terminate(null);
				cancelCallflow.process(inboundRequest);

			});

			setState(QueueState.RINGING);
			sendResponse(inboundRequest.createResponse(180));

			// Create media request object, so it can be used in future timers (if
			// necessary)
			String mediaUri = this.attributes.getAnnouncement();
			if (mediaUri != null) {
				this.mediaRequest = sipFactory.createRequest(appSession, INVITE, aliceRequest.getFrom(),
						sipFactory.createAddress(mediaUri));
				copyContent(aliceRequest, mediaRequest);
			} else {

				if (null != attributes.ringPeriod) {
					setState(QueueState.RINGING);

					ringingPeriodTimer = startTimer(aliceRequest.getApplicationSession(), attributes.ringPeriod * 1000,
							attributes.ringPeriod * 1000, false, false, (timer) -> {
								if (false == stateEquals(QueueState.CANCELED)) {

									if (0 == aliceRequest.getSession().getState().compareTo(State.EARLY)) {
										sendResponse(aliceRequest.createResponse(180));
									}

								}
							});
				}

				// Set a ringing duration timer
				if (attributes.ringDuration != null && attributes.ringDuration > 0 && mediaRequest != null) {

					startTimer(inboundRequest.getApplicationSession(), attributes.ringDuration * 1000, false,
							(timer) -> {
								if (attributes.announcement != null) {

									if (sipLogger.isLoggable(Level.FINER)) {
										sipLogger.finer(aliceRequest,
												"QueueCallflow.process - Expectation cancelWhileRinging cleared...");
									}
									cancelWhileRinging.clear();

									if (false == stateEquals(QueueState.CANCELED)) {

										Expectation cancelWhileCallingMedia = this
												.expectRequest(aliceRequest.getSession(), CANCEL, (cancel) -> {
													if (sipLogger.isLoggable(Level.FINER)) {
														sipLogger.finer(aliceRequest.getSession(),
																"QueueCallflow.process - Expectation cancelWhileCallingMedia invoked...");
													}
													sendRequest(mediaRequest.createCancel());
													setState(QueueState.CANCELED);
												});

										setState(QueueState.MEDIA_RINGING);
										sendRequest(mediaRequest, (mediaResponse) -> {
											if (successful(mediaResponse)) {
												setState(QueueState.MEDIA_CONNECTED);
												sendResponse(createResponse(aliceRequest, mediaResponse),
														(aliceAckOrPrack) -> {
															cancelWhileCallingMedia.clear();
															stopTimers();
															sendAckOrPrack(aliceAckOrPrack, mediaResponse);
														});
											}

											// go back to just ringing state
											else if (failure(mediaResponse)) {
												setState(QueueState.RINGING);
												cancelWhileCallingMedia.clear();
												cancelWhileRinging.reset();
											}

										});

									}

								} else {
									if (false == stateEquals(QueueState.CANCELED)) {
										this.complete();
									}
								}
							});

				}
			}

		} catch (Exception ex) {
			sipLogger.severe(aliceRequest,
					"QueueCallflow.process caught exception " + ex.getClass().getName() + " " + ex.getMessage());
			sipLogger.severe(aliceRequest, ex);
		}

	}

	public void stopTimers() throws ServletException, IOException {
		SipApplicationSession appSession = aliceRequest.getApplicationSession();

		Collection<ServletTimer> timers = appSession.getTimers();
		if (timers != null) {
			for (ServletTimer timer : timers) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(appSession, "QueueCallflow.stopTimers - canceling timer: " + timer.getId());
				}
				timer.cancel();
			}
		}

	}

	public void complete() throws ServletException, IOException {
		SipApplicationSession appSession;
		SipSession sipSession;

		sipSession = aliceRequest.getSession();
		appSession = aliceRequest.getApplicationSession();
		if (appSession != null && appSession.isValid() && sipSession != null && sipSession.isValid()) {

			try {
				if (appSession != null) {

					if (false == this.stateEquals(QueueState.CANCELED)) {
						stopTimers();

						SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, //
								aliceRequest.getFrom(), //
								aliceRequest.getTo());
						bobRequest.setRequestURI(aliceRequest.getRequestURI());

						switch (getState()) {
						case MEDIA_CONNECTED:
							Expectation byeExpectation = this.expectRequest(aliceRequest.getSession(), BYE, (bye) -> {

								try {

									if (sipLogger.isLoggable(Level.FINER)) {
										sipLogger.finer(aliceRequest,
												"QueueCallflow.complete - byeExpectation invoked...");
									}
									sendRequest(bobRequest.createCancel());
									setState(QueueState.CANCELED);

								} catch (Exception ex) {

									sipLogger.severe(aliceRequest,
											"QueueCallflow.complete - byeExpectation caught exception "
													+ ex.getClass().getName() + " " + ex.getMessage());

									sipLogger.severe(aliceRequest, ex);

								}

							});

							sendRequest(bobRequest, (bobResponse) -> {
								if (sipLogger.isLoggable(Level.FINER)) {
									sipLogger.finer(bobResponse,
											"QueueCallflow.complete - sendRequest, response received, status="
													+ bobResponse.getStatus());
								}

								if (successful(bobResponse)) {
									SipServletRequest aliceSDP = aliceRequest.getSession().createRequest(INVITE);
									copyContent(bobResponse, aliceSDP);

									sendRequest(aliceSDP, (aliceAck) -> {
										sendRequest(copyContent(aliceAck, bobResponse.createAck()));
										sendRequest(mediaRequest.getSession().createRequest(BYE));
										byeExpectation.clear();
									});
								}
								if (failure(bobResponse)) {
									// put this back on the queue to try again
									sipLogger.finer(bobResponse,
											"QueueCallflow.complete - Call failure, returning to queue. status="
													+ bobResponse.getStatus() + ", from=" + bobResponse.getFrom()
													+ ", to=" + bobResponse.getTo());
									QueueServlet.queues.get(queueId).callflows.add(this);
								}
							});
							break;

						case MEDIA_RINGING:
							sendRequest(mediaRequest.createCancel(), (media486) -> {
								// do nothing;
							}); // no break, fallthru;
						case RINGING:
							Expectation cancelWhileCalingBob = this.expectRequest(aliceRequest.getSession(), CANCEL,
									(bye) -> {
										sipLogger.finer(aliceRequest.getSession(),
												"QueueCallflow.complete - Expcectation cancelWhileCalingBob invoked...");
										sendRequest(bobRequest.createCancel());
										setState(QueueState.CANCELED);
									});

							sendRequest(copyContentAndHeaders(aliceRequest, bobRequest), (bobResponse) -> {
								sendResponse(createResponse(aliceRequest, bobResponse), (ackOrPrack) -> {
									switch (ackOrPrack.getMethod()) {

									case PRACK:
										sendRequest(copyContent(ackOrPrack, bobResponse.createPrack()));
										break;

									case ACK:
										sendRequest(copyContent(ackOrPrack, bobResponse.createAck()));
										sipLogger.finer(ackOrPrack,
												"QueueCallflow.complete - Expectation cancelWhileCalingBob cleared...");
										cancelWhileCalingBob.clear();
										break;
									}
								});
							});
							break;

						default:
							sipLogger.finer(aliceRequest, "QueueCallflow.complete - default case, state=" + getState());
						}

					}
				}

			} catch (Exception e) {
				sipLogger.warning(aliceRequest,
						"QueueCallflow.complete - Caught Exception " + e.getClass().getName() + " " + e.getMessage());
				e.printStackTrace();
			}

		} else {
			sipLogger.finer(aliceRequest, "QueueCallflow.complete - Invalid SipSession. Nothing to do.");
		}

	}

	public QueueState getState() {
		QueueState state = null;

		SipApplicationSession appSession = aliceRequest.getApplicationSession();
		if (appSession != null && appSession.isValid()) {
			state = (QueueState) appSession.getAttribute("STATE");
		} else {
			state = QueueState.CANCELED;
		}

		return state;
	}

	public void setState(QueueState state) {
		SipApplicationSession appSession = aliceRequest.getApplicationSession();
		appSession.setAttribute("STATE", state);
	}

	public boolean stateEquals(QueueState state) {
		QueueState _state = getState();
		return (null != _state && _state == state) ? true : false;
	}

}
