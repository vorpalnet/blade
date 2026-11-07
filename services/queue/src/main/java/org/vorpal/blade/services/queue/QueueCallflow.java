package org.vorpal.blade.services.queue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.logging.Level;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.networkconnection.NetworkConnection;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;

import org.vorpal.blade.framework.v2.b2bua.Terminate;
import org.vorpal.blade.framework.v2.callflow.Expectation;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;
import org.vorpal.blade.framework.v3.media.CallflowHold;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.services.queue.config.QueueAttributes;

/// One caller in a queue: ring them, optionally answer them with an announcement or with media the
/// queue plays itself, and hand them to the destination when the queue releases them.
///
/// With `media` set the queue is its own announcement server: it answers the caller from the
/// JSR-309 media server and loops the prompt ([#answerWithMedia]). Releasing that caller is RFC 3725
/// flow I ([#releaseFromMedia]): an offerless re-INVITE fetches the caller's fresh offer, the
/// destination answers it, and the caller gets the answer in the ACK. The destination therefore always
/// gets an INVITE with an offer, which some applications need.
///
/// A queue whose destination keeps its own line ([QueueAttributes#getDestinationHolds], presence for
/// a skill) sends each caller on as soon as the media plays, and asks the destination first
/// ([#releaseToHoldingDestination]): the caller hears the media until someone there answers, which
/// may be minutes, so the caller cannot be the one left waiting for an ACK.
public class QueueCallflow extends MediaCallflow {
	private static final long serialVersionUID = 1L;
	public SipServletRequest aliceRequest;
	public SipServletRequest mediaRequest;
	public String ringingPeriodTimer;
	public String queueId;

	/// When the caller joined, for `waitedMs`; how many times they have been
	/// offered to the destination; and whether their leaving was published.
	private long enteredAt;
	private int attempts;
	private boolean left;

	public enum QueueState {
		CANCELED, RINGING, MEDIA_RINGING, MEDIA_CONNECTED, RELEASED
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
				abandoned("caller");
				QueueServlet.releaseMedia(appSession.getId());

				// Try to CANCEL or BYE any outbound requests;
				sipLogger.finer(inboundRequest,
						"QueueCallflow.process.cancelWhileRinging - invoking Cancel.process in case there are outbound requests.");
				Terminate cancelCallflow = new Terminate(null);
				cancelCallflow.process(inboundRequest);

			});

			setState(QueueState.RINGING);
			sendResponse(inboundRequest.createResponse(180));

			// Media the queue plays itself takes the place of an announcement server.
			String mediaUri = this.attributes.getAnnouncement();
			if (!playsMedia() && mediaUri != null) {
				this.mediaRequest = sipFactory.createRequest(appSession, INVITE, aliceRequest.getFrom(),
						sipFactory.createAddress(mediaUri));
				copyContent(aliceRequest, mediaRequest);
			}

			// Keep the caller's phone ringing while they wait unanswered.
			if (null != attributes.ringPeriod) {
				ringingPeriodTimer = startTimer(aliceRequest.getApplicationSession(), attributes.ringPeriod * 1000,
						attributes.ringPeriod * 1000, false, false, (timer) -> {
							if (false == stateEquals(QueueState.CANCELED)) {
								if (0 == aliceRequest.getSession().getState().compareTo(State.EARLY)) {
									sendResponse(aliceRequest.createResponse(180));
								}
							}
						});
			}

			// After ringDuration seconds (or at once), answer with the announcement or the media.
			if (playsMedia() || mediaRequest != null) {
				int seconds = (attributes.ringDuration == null) ? 0 : attributes.ringDuration;
				if (seconds <= 0) {
					answerWhileWaiting(cancelWhileRinging);
				} else {
					startTimer(appSession, seconds * 1000, false, (timer) -> answerWhileWaiting(cancelWhileRinging));
				}
			}

			// Now fully initialized: aliceRequest is set, the caller is ringing and
			// the cancel expectation is armed. Only now is it safe for the drain
			// timer (Queue.queueTask -> complete()) to pull this callflow.
			// addFirst + pollLast = FIFO: newest at the head, drain from the tail.
			Queue queue = QueueServlet.queues.get(queueId);
			if (!destinationHolds()) {
				queue.callflows.addFirst(this);
			}
			enteredAt = System.currentTimeMillis();
			int depth = queue.callflows.size();
			Events.publish(aliceRequest.getApplicationSession(), BladeEventTypes.QUEUE_ENTERED,
					data -> data.put("queue", queueId).put("depth", depth));

		} catch (Exception ex) {
			sipLogger.severe(aliceRequest,
					"QueueCallflow.process caught exception " + ex.getClass().getName() + " " + ex.getMessage());
			sipLogger.severe(aliceRequest, ex);
		}

	}

	/// True when this queue plays `media` itself and the media server is connected.
	private boolean playsMedia() {
		return attributes.getMedia() != null && getMsControlFactory() != null;
	}

	/// True when the destination keeps its own line and this queue's job is only the media.
	private boolean destinationHolds() {
		return Boolean.TRUE.equals(attributes.getDestinationHolds()) && playsMedia();
	}

	/// The caller has rung long enough: answer them with the queue's media or connect them to the
	/// announcement server. Does nothing once they were released or hung up.
	private void answerWhileWaiting(Expectation cancelWhileRinging) throws ServletException, IOException {
		if (!stateEquals(QueueState.RINGING)) {
			return;
		}
		if (playsMedia()) {
			answerWithMedia(cancelWhileRinging);
		} else if (mediaRequest != null) {
			connectAnnouncement(cancelWhileRinging);
		}
	}

	/// Answer the caller from the media server and loop `media` to them until they are released or
	/// hang up. A caller whose INVITE has no offer keeps ringing: the media server answers an offer.
	private void answerWithMedia(Expectation cancelWhileRinging) throws ServletException, IOException {
		SipApplicationSession app = aliceRequest.getApplicationSession();
		String appId = app.getId();
		byte[] callerOffer = rawContent(aliceRequest);
		if (callerOffer == null || callerOffer.length == 0) {
			sipLogger.warning(aliceRequest, "QueueCallflow: the caller's INVITE has no offer; ringing instead of playing media");
			if (destinationHolds()) {
				complete(); // no tick will: send them on, ringing
			}
			return;
		}
		try {
			MediaSession ms = createMediaSession(app);
			NetworkConnection nc = ms.createNetworkConnection(NetworkConnection.BASIC);
			QueueServlet.LIVE.put(appId, new QueueServlet.Waiting(ms, nc));
			setState(QueueState.MEDIA_RINGING);
			offer(nc, callerOffer, (answer) -> {
				if (!stateEquals(QueueState.MEDIA_RINGING)) {
					return; // released, or hung up, while the media server answered
				}
				if (!answer.isSuccessful()) {
					sipLogger.warning(aliceRequest, "QueueCallflow: the media server refused the caller's offer: "
							+ answer.getErrorText() + "; ringing instead");
					QueueServlet.releaseMedia(appId);
					setState(QueueState.RINGING);
					if (destinationHolds()) {
						complete();
					}
					return;
				}
				SipServletResponse ok = aliceRequest.createResponse(200);
				ok.setContent(answer.getMediaServerSdp(), "application/sdp");
				cancelWhileRinging.clear();
				setState(QueueState.MEDIA_CONNECTED);
				expectRequest(aliceRequest.getSession(), BYE, (bye) -> {
					sendResponse(bye.createResponse(200));
					QueueServlet.releaseMedia(appId);
					setState(QueueState.CANCELED);
					abandoned("caller");
				});
				sendResponse(ok, (ack) -> {
					stopTimers();
					playWhileWaiting(appId);
					if (destinationHolds()) {
						releaseToHoldingDestination();
					}
				});
			});
		} catch (MsControlException e) {
			sipLogger.severe(aliceRequest, "QueueCallflow: no media for the waiting caller, ringing instead: " + e.getMessage());
			QueueServlet.releaseMedia(appId);
			setState(QueueState.RINGING);
			if (Boolean.TRUE.equals(attributes.getDestinationHolds())) {
				complete();
			}
		}
	}

	/// Play `media` to the waiting caller, again each time it ends, until they leave the media.
	private void playWhileWaiting(String appId) {
		QueueServlet.Waiting waiting = QueueServlet.LIVE.get(appId);
		if (waiting == null) {
			return;
		}
		try {
			if (waiting.mg == null) {
				waiting.mg = waiting.ms.createMediaGroup(MediaGroup.PLAYER);
				join(waiting.mg, Joinable.Direction.DUPLEX, waiting.nc);
			}
			play(waiting.mg, new URI[] { URI.create(attributes.getMedia()) }, (done) -> {
				if (!done.isSuccessful()) {
					// A prompt that cannot play fails at once, every time: no loop.
					if (stateEquals(QueueState.MEDIA_CONNECTED)) {
						sipLogger.warning(aliceRequest, "QueueCallflow: cannot play " + attributes.getMedia() + ": "
								+ done.getErrorText());
					}
					return;
				}
				if (stateEquals(QueueState.MEDIA_CONNECTED)) {
					playWhileWaiting(appId);
				}
			});
		} catch (MsControlException e) {
			sipLogger.warning(aliceRequest, "QueueCallflow: cannot play to the waiting caller: " + e.getMessage());
		}
	}

	/// Connect the caller to the announcement server while they wait.
	private void connectAnnouncement(Expectation cancelWhileRinging) throws ServletException, IOException {
		cancelWhileRinging.clear();

		Expectation cancelWhileCallingMedia = this.expectRequest(aliceRequest.getSession(), CANCEL, (cancel) -> {
			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(aliceRequest.getSession(),
						"QueueCallflow.connectAnnouncement - Expectation cancelWhileCallingMedia invoked...");
			}
			sendRequest(mediaRequest.createCancel());
			setState(QueueState.CANCELED);
			abandoned("caller");
		});

		setState(QueueState.MEDIA_RINGING);
		sendRequest(mediaRequest, (mediaResponse) -> {
			if (successful(mediaResponse)) {
				setState(QueueState.MEDIA_CONNECTED);
				sendResponse(createResponse(aliceRequest, mediaResponse), (aliceAckOrPrack) -> {
					cancelWhileCallingMedia.clear();
					stopTimers();
					sendAcknowledgement(aliceAckOrPrack, mediaResponse);
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

	/// Send a caller who hears the queue's media to a destination that keeps its own line, asking the
	/// destination first: an INVITE with no offer, which may wait there for minutes (presence answers
	/// 182 Queued). The destination's 200 carries its offer; the caller is re-INVITEd with it and
	/// answers at once, and that answer goes to the destination in the ACK. The media plays until
	/// then and is released only once the caller is connected.
	///
	/// A refusal is final: the destination already did the waiting, so trying again would only
	/// rejoin the back of its line. The caller is hung up.
	private void releaseToHoldingDestination() throws ServletException, IOException {
		SipApplicationSession appSession = aliceRequest.getApplicationSession();
		String appId = appSession.getId();
		attempts++;
		int attempt = attempts;
		Events.publish(appSession, BladeEventTypes.QUEUE_RELEASED, data -> data
				.put("queue", queueId).put("waitedMs", waited()).put("attempt", attempt));

		SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, aliceRequest.getFrom(),
				aliceRequest.getTo());
		copyHeaders(aliceRequest, bobRequest);
		bobRequest.setRequestURI(aliceRequest.getRequestURI());

		Expectation byeWhileWaiting = expectRequest(aliceRequest.getSession(), BYE, (bye) -> {
			sendResponse(bye.createResponse(200));
			if (bobRequest.isCommitted()) {
				if (bobRequest.getSession().getState() == State.CONFIRMED) {
					sendRequest(bobRequest.getSession().createRequest(BYE));
				} else {
					sendRequest(bobRequest.createCancel());
				}
			}
			QueueServlet.releaseMedia(appId);
			setState(QueueState.CANCELED);
			abandoned("caller");
		});

		sendRequest(bobRequest, (bobResponse) -> {
			if (successful(bobResponse)) {
				SipServletRequest reinvite = aliceRequest.getSession().createRequest(INVITE);
				copyContent(bobResponse, reinvite);
				sendRequest(reinvite, (aliceOk) -> {
					if (successful(aliceOk)) {
						sendRequest(aliceOk.createAck());
						sendRequest(copyContent(aliceOk, bobResponse.createAck()));
						byeWhileWaiting.clear();
						setState(QueueState.RELEASED);
						linkSession(aliceRequest.getSession(), bobResponse.getSession());
						QueueServlet.releaseMedia(appId);
					} else if (failure(aliceOk)) {
						// The caller would not take the destination's offer. The destination's 200 is
						// still owed an ACK; then both are hung up.
						sendRequest(bobResponse.createAck());
						sendRequest(bobResponse.getSession().createRequest(BYE));
						byeWhileWaiting.clear();
						sendRequest(aliceRequest.getSession().createRequest(BYE));
						QueueServlet.releaseMedia(appId);
						setState(QueueState.CANCELED);
						abandoned("refused");
					}
				});
			} else if (failure(bobResponse)) {
				sipLogger.warning(bobResponse, "QueueCallflow.releaseToHoldingDestination - the destination refused, status="
						+ bobResponse.getStatus() + "; hanging up");
				byeWhileWaiting.clear();
				sendRequest(aliceRequest.getSession().createRequest(BYE));
				QueueServlet.releaseMedia(appId);
				setState(QueueState.CANCELED);
				abandoned("refused");
			}
		});
	}

	/// Move a caller who hears the queue's media to the destination, RFC 3725 flow I: an offerless
	/// re-INVITE gets the caller's fresh offer, the destination answers it, and the caller gets that
	/// answer in the ACK. The media is released only once the caller is connected.
	private void releaseFromMedia(SipServletRequest bobRequest) throws ServletException, IOException {
		String appId = aliceRequest.getApplicationSession().getId();
		Expectation byeWhileReleasing = expectRequest(aliceRequest.getSession(), BYE, (bye) -> {
			sendResponse(bye.createResponse(200));
			if (bobRequest.isCommitted()) {
				if (bobRequest.getSession().getState() == State.CONFIRMED) {
					sendRequest(bobRequest.getSession().createRequest(BYE));
				} else {
					sendRequest(bobRequest.createCancel());
				}
			}
			QueueServlet.releaseMedia(appId);
			setState(QueueState.CANCELED);
			abandoned("caller");
		});

		sendRequest(aliceRequest.getSession().createRequest(INVITE), (aliceOk) -> {
			if (failure(aliceOk)) {
				// The caller refused the re-INVITE and still hears the queue: try again next tick.
				QueueServlet.queues.get(queueId).callflows.add(this);
				return;
			}
			if (!successful(aliceOk)) {
				return;
			}
			copyContent(aliceOk, bobRequest);
			sendRequest(bobRequest, (bobResponse) -> {
				if (successful(bobResponse)) {
					sendRequest(copyContent(bobResponse, aliceOk.createAck()));
					sendRequest(bobResponse.createAck());
					byeWhileReleasing.clear();
					setState(QueueState.RELEASED);
					linkSession(aliceRequest.getSession(), bobResponse.getSession());
					QueueServlet.releaseMedia(appId);
				} else if (failure(bobResponse)) {
					// The caller's 200 is owed an ACK with an answer: an inactive one, until the next try.
					SipServletRequest ack = aliceOk.createAck();
					String inactive = CallflowHold.inactiveAnswerFor(aliceOk);
					if (inactive != null) {
						ack.setContent(inactive.getBytes(StandardCharsets.UTF_8), "application/sdp");
					}
					sendRequest(ack);
					int status = bobResponse.getStatus();
					if (retryable(status)) {
						sipLogger.warning(bobResponse,
								"QueueCallflow.releaseFromMedia - Retryable call failure, returning to queue. status=" + status);
						QueueServlet.queues.get(queueId).callflows.add(this);
					} else {
						sipLogger.warning(bobResponse,
								"QueueCallflow.releaseFromMedia - Call failure, removing from queue. status=" + status);
						byeWhileReleasing.clear();
						sendRequest(aliceRequest.getSession().createRequest(BYE));
						QueueServlet.releaseMedia(appId);
						setState(QueueState.CANCELED);
						abandoned("refused");
					}
				}
			});
		});
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

	/// A failed release attempt worth retrying: the destination may recover.
	/// 408 (transaction timeout — the unreachable-network case), 480 Temporarily
	/// Unavailable, 486 Busy Here, and 5xx server failures except 501 Not
	/// Implemented. Everything else — the rest of 4xx and all of 6xx — is
	/// definitive: retrying re-fails forever, so the call leaves the queue.
	private static boolean retryable(int status) {
		switch (status) {
		case 408:
		case 480:
		case 486:
			return true;
		default:
			return status >= 500 && status < 600 && status != 501;
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
						attempts++;
						int attempt = attempts;
						Events.publish(appSession, BladeEventTypes.QUEUE_RELEASED, data -> data
								.put("queue", queueId).put("waitedMs", waited()).put("attempt", attempt));

						SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, //
								aliceRequest.getFrom(), //
								aliceRequest.getTo());
						bobRequest.setRequestURI(aliceRequest.getRequestURI());

						switch (getState()) {
						case MEDIA_CONNECTED:
							if (QueueServlet.LIVE.containsKey(appSession.getId())) {
								copyContentAndHeaders(aliceRequest, bobRequest);
								releaseFromMedia(bobRequest);
								break;
							}
							Expectation byeExpectation = this.expectRequest(aliceRequest.getSession(), BYE, (bye) -> {

								try {

									if (sipLogger.isLoggable(Level.FINER)) {
										sipLogger.finer(aliceRequest,
												"QueueCallflow.complete - byeExpectation invoked...");
									}
									sendRequest(bobRequest.createCancel());
									setState(QueueState.CANCELED);
									abandoned("caller");

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
										setState(QueueState.RELEASED);
										linkSession(aliceRequest.getSession(), bobResponse.getSession());
									});
								}
								if (failure(bobResponse)) {
									int status = bobResponse.getStatus();
									if (retryable(status)) {
										// The destination may recover; back on the release end
										// of the queue so this call keeps its priority and is
										// retried on the next tick. Bounded by the caller
										// hanging up (byeExpectation stays armed) and by the
										// app session expiration.
										sipLogger.warning(bobResponse,
												"QueueCallflow.complete - Retryable call failure, returning to queue. status="
														+ status + ", from=" + bobResponse.getFrom()
														+ ", to=" + bobResponse.getTo());
										QueueServlet.queues.get(queueId).callflows.add(this);
									} else {
										// Definitive failure: this call can never succeed. End both dialogs.
										sipLogger.warning(bobResponse,
												"QueueCallflow.complete - Call failure, removing from queue. status="
														+ status + ", from=" + bobResponse.getFrom()
														+ ", to=" + bobResponse.getTo());
										byeExpectation.clear();
										sendRequest(mediaRequest.getSession().createRequest(BYE));
										sendRequest(aliceRequest.getSession().createRequest(BYE));
										setState(QueueState.CANCELED);
										abandoned("refused");
									}
								}
							});
							break;

						case MEDIA_RINGING:
							if (QueueServlet.LIVE.containsKey(appSession.getId())) {
								// The media server has not answered yet: drop it and connect directly.
								QueueServlet.releaseMedia(appSession.getId());
								setState(QueueState.RINGING);
							} else {
								sendRequest(mediaRequest.createCancel(), (media486) -> {
									// do nothing;
								});
							} // no break, fallthru;
						case RINGING:
							Expectation cancelWhileCalingBob = this.expectRequest(aliceRequest.getSession(), CANCEL,
									(bye) -> {
										sipLogger.finer(aliceRequest.getSession(),
												"QueueCallflow.complete - Expcectation cancelWhileCalingBob invoked...");
										sendResponse(bye.createResponse(200));
										sendRequest(bobRequest.createCancel());
										setState(QueueState.CANCELED);
										abandoned("caller");
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

	/// Publish [BladeEventTypes#QUEUE_ABANDONED], once, however the caller left.
	///
	/// @param reason `caller` when they hung up, `refused` when the destination
	///               refused for good
	void abandoned(String reason) {
		if (left) {
			return;
		}
		left = true;
		SipApplicationSession appSession = (aliceRequest == null) ? null : aliceRequest.getApplicationSession();
		Events.publish(appSession, BladeEventTypes.QUEUE_ABANDONED,
				data -> data.put("queue", queueId).put("waitedMs", waited()).put("reason", reason));
	}

	long waited() {
		return (enteredAt == 0) ? 0 : System.currentTimeMillis() - enteredAt;
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
