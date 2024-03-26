package org.vorpal.blade.services.queue;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.callflow.Expectation;
import org.vorpal.blade.services.queue.config.QueueAttributes;

public class QueueCallflow extends Callflow {
	private SipServletRequest aliceRequest;
	private SipServletRequest mediaRequest;
	private String ringingPeriodTimer;
	private String queueId;

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
		SipSession sipSession = inboundRequest.getSession();
		this.aliceRequest = inboundRequest;

		Expectation cancelWhileRinging = this.expectRequest(aliceRequest.getSession(), CANCEL, (cancel) -> {
			sipLogger.finer(aliceRequest.getSession(), "Expectation cancelWhileRinging invoked...");
			stopTimers();
			setState(QueueState.CANCELED);
		});

		if (attributes.ringDuration != null && attributes.ringDuration > 0) {
			sendResponse(inboundRequest.createResponse(180));
		}

		// Create media request object, so it can be used in future timers (if
		// necessary)
		String mediaUri = this.attributes.getAnnouncement();
		if (mediaUri != null) {
			this.mediaRequest = sipFactory.createRequest(appSession, INVITE, aliceRequest.getFrom(),
					sipFactory.createAddress(mediaUri));
			copyContent(aliceRequest, mediaRequest);

		}

		// set a ringing period timer to ring every 30 seconds (if needed)
//		if (attributes.ringDuration != null && attributes.ringDuration > attributes.ringPeriod) {
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

			startTimer(inboundRequest.getApplicationSession(), attributes.ringDuration * 1000, false, (timer) -> {
				if (attributes.announcement != null) {

					sipLogger.finer(aliceRequest, "Expectation cancelWhileRinging cleared...");
					cancelWhileRinging.clear();

					if (false == stateEquals(QueueState.CANCELED)) {

						Expectation cancelWhileCallingMedia = this.expectRequest(aliceRequest.getSession(), CANCEL,
								(cancel) -> {
									sipLogger.finer(aliceRequest.getSession(),
											"Expectation cancelWhileCallingMedia invoked...");
									sendRequest(mediaRequest.createCancel());
									setState(QueueState.CANCELED);
								});

						setState(QueueState.MEDIA_RINGING);
						sendRequest(mediaRequest, (mediaResponse) -> {
							if (successful(mediaResponse)) {
								sipLogger.severe(mediaResponse, "Setting queue state to MEDIA");
								setState(QueueState.MEDIA_CONNECTED);
								sendResponse(createResponse(aliceRequest, mediaResponse), (aliceAckOrPrack) -> {
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

//								Expectation cancelWhileRinging2 = this.expectRequest(aliceRequest.getSession(), CANCEL,
//										(cancel) -> {
//											sipLogger.warning(aliceRequest.getSession(),
//													"Expectation cancelWhileRinging2 invoked...");
//											stopTimers();
//											setState(QueueState.CANCELED);
//										});
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

	public void stopTimers() throws ServletException, IOException {
		SipApplicationSession appSession = aliceRequest.getApplicationSession();

		Collection<ServletTimer> timers = appSession.getTimers();
		if (timers != null) {
			for (ServletTimer timer : timers) {
				sipLogger.finer(appSession, "canceling timer: " + timer.getId());
				timer.cancel();
			}
		}

	}

	public void complete() throws ServletException, IOException {

		try {

			SipApplicationSession appSession = aliceRequest.getApplicationSession();

			sipLogger.finer(appSession, "is the app session null? " + ((appSession == null) ? true : false));

			if (appSession != null) {

				sipLogger.finer(appSession, "is the queue state canceled? " + this.stateEquals(QueueState.CANCELED));
				sipLogger.finer(appSession, "is the app session valid? " + appSession.isValid());
				sipLogger.finer(appSession, "is the sip session valid? " + aliceRequest.getSession().isValid());
				sipLogger.finer(appSession, "What is the sip session state? " + aliceRequest.getSession().getState());
				sipLogger.finer(appSession, "What is the queue state? " + getState());

				if (false == this.stateEquals(QueueState.CANCELED)) {
					stopTimers();

					SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, //
							aliceRequest.getFrom(), //
							aliceRequest.getTo());
					bobRequest.setRequestURI(aliceRequest.getRequestURI());

					// For testing... DELME!
					if (null == getState()) {
						sipLogger.severe(appSession, "State is NULL! How can this be?");
					}

					switch (getState()) {
					case MEDIA_CONNECTED:
						Expectation byeExpectation = this.expectRequest(aliceRequest.getSession(), BYE, (bye) -> {
							sipLogger.finer(aliceRequest.getSession(), "Expcectation byeExpectation invoked...");
							sendRequest(bobRequest.createCancel());
							setState(QueueState.CANCELED);
						});

						sendRequest(bobRequest, (bobResponse) -> {
							if (successful(bobResponse)) {
								SipServletRequest aliceSDP = aliceRequest.getSession().createRequest(INVITE);
								this.copyContent(bobResponse, aliceSDP);
								sendRequest(aliceSDP, (aliceAck) -> {
									linkSessions(bobResponse.getSession(), aliceAck.getSession());
									sendRequest(copyContent(aliceAck, bobResponse.createAck()));
									sendRequest(mediaRequest.getSession().createRequest(BYE));
									sipLogger.finer(aliceAck, "Expectation byeExpectation cleared...");
									byeExpectation.clear();
								});
							}
							if (failure(bobResponse)) {
								// put this back on the queue to try again
								sipLogger.finer(bobResponse,
										"Call failure, returning to queue. status=" + bobResponse.getStatus()
												+ ", from=" + bobResponse.getFrom() + ", to=" + bobResponse.getTo());
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
											"Expcectation cancelWhileCalingBob invoked...");
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
									sipLogger.finer(ackOrPrack, "Expectation cancelWhileCalingBob cleared...");
									cancelWhileCalingBob.clear();
									break;
								}
							});
						});

					}

				}
			}

		} catch (Exception e) {
			sipLogger.severe(e.getMessage());
			sipLogger.logStackTrace(e);
		}

	}

	public QueueState getState() {
		QueueState state = null;

		SipApplicationSession appSession = aliceRequest.getApplicationSession();
		if (appSession != null) {
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
