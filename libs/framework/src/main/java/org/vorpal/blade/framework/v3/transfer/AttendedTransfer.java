package org.vorpal.blade.framework.v3.transfer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v2.callflow.Expectation;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.transfer.BlindTransfer;
import org.vorpal.blade.framework.v2.transfer.TransferListener;
import org.vorpal.blade.framework.v2.transfer.TransferSettings;
import org.vorpal.blade.framework.v3.media.CallflowHold;

/// An attended (consultative) transfer. Alice is the customer, Bob the agent
/// who transfers her, Carol the agent he transfers her to.
///
/// Every phone keeps exactly one dialog, with this service, for the whole
/// transfer. None of them receives a REFER or knows a transfer is happening:
/// the service decides who hears whom with re-INVITEs (RFC 3725 third-party
/// call control). Carol's leg is a third dialog in the same application
/// session as Alice's and Bob's.
///
/// | When | What the service does |
/// |---|---|
/// | the REFER arrives (or the REST API builds one) | INVITE Carol with no offer; Alice and Bob stay connected |
/// | Carol never answers, or declines | nothing changes; Bob is told the transfer failed |
/// | Carol answers | park Alice (`a=inactive`), connect Bob and Carol |
/// | Bob hangs up on Carol | connect Alice and Carol; the transfer is complete |
/// | Carol hangs up before Alice reaches her | reconnect Alice and Bob, ready for another transfer |
/// | Bob hangs up while Carol still rings | carried out as a blind transfer: Alice meets Carol when she answers |
/// | Carol hangs up after Bob has gone | Alice goes back to the destination she first called (the agent pool), so a new Bob |
/// | Alice hangs up at any point | Bob and Carol are hung up (Carol cancelled if still ringing) |
///
/// Until Carol answers, the transfer is a [BlindTransfer]'s ringing phase, so
/// it is that class's code that runs; this class takes over at
/// [#targetAnswered], and hands back to it when Bob has already gone.
///
/// The base class is always built with NOTIFYs off. That is the setting under
/// which it hangs up on Bob when Alice leaves early, which an attended
/// transfer needs too. The NOTIFYs a transferor's REFER subscription is owed
/// are sent here instead: `100 Trying` when the REFER is accepted, a failure
/// when Carol declines or leaves the consultation, and none on success,
/// because by then Bob has hung up. The final NOTIFY is also what clears the
/// framework's one-transfer-per-dialog guard, so a reconnected Bob can try
/// again while a second REFER during the transfer is refused 491.
///
/// ## Events
///
/// The same five as a blind transfer, each meaning exactly what it says, on
/// the bus and to the listener alike: `transferRequested`, `transferInitiated`
/// (the INVITE to Carol), `transferCompleted` (Alice connected to Carol, not
/// Carol answering), `transferDeclined` (Carol refused, never answered, hung
/// up during the consultation, or was lost before Alice reached her) and
/// `transferAbandoned` (Alice hung up). Carol answering is reported to a
/// [TransferObserver], as [TransferObserver#consultationStarted]: the
/// transfer service publishes it as its own event, and the REST API's waited
/// request answers then, because a consultation can outlast any HTTP timeout.
///
/// A `Refer-To` carrying `?Replaces=` selects this callflow whatever the
/// configured style. The header names a dialog the sender knows, not one Carol
/// has with this service, so it is removed from the INVITE to Carol.
public class AttendedTransfer extends BlindTransfer {
	private static final long serialVersionUID = 1L;

	/// Application-session attributes for a running attended transfer: the
	/// three dialogs' SipSession ids and the current phase. Ids, not sessions,
	/// so every callback finds the live dialogs however the call state was
	/// stored in between.
	public static final String TRANSFEREE_ATTR = "org.vorpal.blade.v3.transfer.attended.transferee";
	public static final String TRANSFEROR_ATTR = "org.vorpal.blade.v3.transfer.attended.transferor";
	public static final String TARGET_ATTR = "org.vorpal.blade.v3.transfer.attended.target";
	public static final String PHASE_ATTR = "org.vorpal.blade.v3.transfer.attended.phase";

	/// Set when Bob hangs up while Carol still rings. His dialog is not yet
	/// TERMINATED when his BYE has been answered, so its state cannot say he
	/// has gone; this attribute does.
	public static final String TRANSFEROR_GONE_ATTR = "org.vorpal.blade.v3.transfer.attended.transferorGone";

	/// The phases, in order. A handler that finds the transfer in a later
	/// phase than the one it was set for answers its request and does nothing
	/// else, so crossing BYEs never start two recoveries.
	static final String RINGING = "ringing";
	static final String CONSULTING = "consulting";
	static final String COMPLETING = "completing";
	static final String RECONNECTING = "reconnecting";
	static final String RETURNING = "returning";

	/// Written by `TransferInitialInvite` on the application session: the
	/// INVITE that started the call, whose Request-URI is the pool a new Bob
	/// comes from.
	private static final String INITIAL_INVITE_ATTR = "INITIAL_INVITE";

	/// RFC 3261 section 14.1: the owner of the Call-ID retries a 491 after 2.1
	/// to 4 seconds. This service owns every dialog's re-INVITEs here.
	private static final long GLARE_RETRY_MS = 2500;

	private static final String PENDING_EXPIRES = "pending;expires=3600";
	private static final String TERMINATED_REJECTED = "terminated;reason=rejected";
	private static final String SDP = "application/sdp";

	private final boolean notifyTransferor;

	/// For a REFER: the transferor's subscription gets its NOTIFYs.
	public AttendedTransfer(TransferListener transferListener, TransferSettings transferSettings) {
		this(transferListener, transferSettings, true);
	}

	/// @param notifyTransferor true for a REFER from the transferor, false for
	///                         the REST API, where no one subscribed
	public AttendedTransfer(TransferListener transferListener, TransferSettings transferSettings,
			boolean notifyTransferor) {
		super(new Outcomes(transferListener), transferSettings, false);
		this.notifyTransferor = notifyTransferor;
		((Outcomes) this.transferListener).owner = this;
	}

	/// Whether `refer` asks for an attended transfer by itself: its `Refer-To`
	/// URI carries a `Replaces` header.
	public static boolean hasReplaces(SipServletRequest refer) {
		try {
			javax.servlet.sip.Address referTo = refer.getAddressHeader(REFER_TO);
			if (referTo != null && referTo.getURI() instanceof SipURI) {
				Iterator<String> names = ((SipURI) referTo.getURI()).getHeaderNames();
				while (names.hasNext()) {
					if ("Replaces".equalsIgnoreCase(names.next())) {
						return true;
					}
				}
			}
		} catch (Exception e) {
			sipLogger.warning(refer, "AttendedTransfer.hasReplaces - unreadable Refer-To: " + e.getMessage());
		}
		return false;
	}

	@Override
	public void process(SipServletRequest refer) throws ServletException, IOException {
		super.process(refer);
		if (targetRequest == null || transfereeRequest == null || transferorRequest == null) {
			return; // the base class refused it (no Refer-To) and said why
		}

		SipApplicationSession appSession = refer.getApplicationSession();
		SipSession alice = transfereeRequest.getSession();
		appSession.setAttribute(TRANSFEREE_ATTR, alice.getId());
		appSession.setAttribute(TRANSFEROR_ATTR, transferorRequest.getSession().getId());
		appSession.setAttribute(TARGET_ATTR, targetRequest.getSession().getId());
		appSession.setAttribute(PHASE_ATTR, RINGING);

		// The REST API refuses a second transfer on a dialog marked this way
		// (TransferAPI's 491); a REFER marks Bob's dialog itself.
		setReferPending(alice, true);

		if (notifyTransferor) {
			notifyTransferor(appSession, PENDING_EXPIRES, TRYING_100);
		}

		// Bob may hang up while Carol rings; the transfer then completes blind.
		// The base class answers that BYE too; this also records it.
		expectRequest(transferorRequest.getSession(), BYE, bye -> {
			sendResponse(bye.createResponse(200));
			if (RINGING.equals(phase(appSession))) {
				appSession.setAttribute(TRANSFEROR_GONE_ATTR, Boolean.TRUE);
			}
		});
	}

	/// Removes any URI headers (`?Replaces=...`) from the INVITE to Carol's
	/// Request-URI and To: they describe a dialog of the REFER's sender, which
	/// Carol does not have with this service.
	@Override
	protected void createRequests(SipServletRequest request) throws ServletException, IOException {
		super.createRequests(request);
		URI requestUri = targetRequest.getRequestURI();
		if (requestUri instanceof SipURI && stripHeaders((SipURI) requestUri)) {
			targetRequest.setRequestURI(requestUri);
		}
		URI toUri = targetRequest.getTo().getURI();
		if (toUri instanceof SipURI && stripHeaders((SipURI) toUri)) {
			targetRequest.getTo().setURI(toUri);
		}
	}

	private static boolean stripHeaders(SipURI uri) {
		List<String> names = new ArrayList<>();
		uri.getHeaderNames().forEachRemaining(names::add);
		for (String name : names) {
			uri.removeHeader(name);
		}
		return !names.isEmpty();
	}

	/// Carol answered. If Bob has already hung up, finish as the blind
	/// transfer it has become; if Alice has, Carol is hung up; otherwise the
	/// consultation starts.
	@Override
	protected void targetAnswered(SipServletRequest refer, SipServletResponse carolOk,
			Expectation aliceExpectation, Expectation bobExpectation) throws ServletException, IOException {
		SipApplicationSession appSession = carolOk.getApplicationSession();
		SipSession alice = session(appSession, TRANSFEREE_ATTR);
		SipSession bob = session(appSession, TRANSFEROR_ATTR);
		SipSession carol = carolOk.getSession();

		if (phase(appSession) == null || !live(alice)) {
			// Alice's BYE crossed Carol's 200: the base class could not cancel her.
			sipLogger.fine(carolOk, "AttendedTransfer - transferee gone before the target answered; hanging up the target");
			sendRequest(parkedAck(carolOk));
			sendRequest(carol.createRequest(BYE));
			finish(appSession);
			return;
		}

		if (!live(bob) || appSession.getAttribute(TRANSFEROR_GONE_ATTR) != null) {
			sipLogger.fine(carolOk, "AttendedTransfer - transferor hung up while the target rang; completing blind");
			finish(appSession);
			super.targetAnswered(refer, carolOk, aliceExpectation, bobExpectation);
			return;
		}

		// The ringing-phase expectations belong to the base class's flow.
		aliceExpectation.clear();
		bobExpectation.clear();
		appSession.setAttribute(PHASE_ATTR, CONSULTING);
		sipLogger.fine(carolOk, "AttendedTransfer - target answered; consultation between transferor and target");
		if (observer() != null) {
			observer().consultationStarted(carolOk);
		}

		expectRequest(bob, BYE, bye -> {
			sendResponse(bye.createResponse(200));
			if (CONSULTING.equals(phase(appSession))) {
				complete(appSession);
			}
		});
		expectRequest(carol, BYE, bye -> {
			sendResponse(bye.createResponse(200));
			if (CONSULTING.equals(phase(appSession))) {
				targetLeft(appSession, bye);
			}
		});
		expectTransfereeBye(appSession);
		parkTransfereeReinvites(appSession);

		unlinkSessions(alice, null);
		linkBoth(bob, carol);
		park(appSession, alice);

		// Carol's offer to Bob in a re-INVITE, Bob's answer back in Carol's ACK.
		SipServletRequest toBob = bob.createRequest(INVITE);
		copyContent(carolOk, toBob);
		sendRequest(toBob, bobResponse -> {
			if (provisional(bobResponse)) {
				return;
			}
			if (successful(bobResponse)) {
				sendRequest(bobResponse.createAck());
				sendRequest(copyContent(bobResponse, carolOk.createAck()));
				return;
			}
			// Carol's 200 still needs its ACK, so she is parked. A BYE from Bob
			// completes the transfer; a glare is retried as a fresh exchange.
			// Carol's 200 is ACKed whatever happened meanwhile; she is parked.
			if (carol.isValid()) {
				try {
					sendRequest(parkedAck(carolOk));
				} catch (IllegalStateException e) {
					sipLogger.fine(carolOk, "AttendedTransfer - target's dialog already gone: " + e.getMessage());
				}
			}
			if (!CONSULTING.equals(phase(appSession))) {
				return; // the consultation ended while Bob's re-INVITE was out
			}
			sipLogger.warning(bobResponse, "AttendedTransfer - transferor refused the target's offer with "
					+ bobResponse.getStatus() + "; target parked");
			if (bobResponse.getStatus() == 491) {
				startTimer(appSession, GLARE_RETRY_MS, false, timer -> {
					SipSession b = session(appSession, TRANSFEROR_ATTR);
					SipSession c = session(appSession, TARGET_ATTR);
					if (CONSULTING.equals(phase(appSession)) && live(b) && live(c)) {
						connect(appSession, c, b, 0, ok -> {
						}, failed -> sipLogger.warning(failed,
								"AttendedTransfer - transferor and target could not be connected; target parked"));
					}
				});
			}
		});
	}

	/// Bob hung up on Carol: connect Alice and Carol.
	private void complete(SipApplicationSession appSession) throws ServletException, IOException {
		appSession.setAttribute(PHASE_ATTR, COMPLETING);
		SipSession alice = session(appSession, TRANSFEREE_ATTR);
		SipSession carol = session(appSession, TARGET_ATTR);
		sipLogger.fine(appSession, "AttendedTransfer - transferor hung up; connecting transferee and target");

		expectRequest(carol, BYE, bye -> {
			sendResponse(bye.createResponse(200));
			if (COMPLETING.equals(phase(appSession))) {
				declined(bye);
				returnToPool(appSession);
			}
		});

		connect(appSession, carol, alice, 0, carolOffer -> {
			clearExpectation(alice, BYE);
			clearExpectation(alice, INVITE);
			clearExpectation(carol, BYE);
			finish(appSession);

			SettingsManager.createEvent("transferCompleted", carolOffer);
			listener().transferCompleted(carolOffer);
			SettingsManager.sendEvent(carolOffer);

			carol.setAttribute(USER_AGENT, CALLEE);
			alice.setAttribute(USER_AGENT, CALLER);
			appSession.setAttribute(X_PREVIOUS_DN, appSession.getAttribute(REFER_TO));
		}, failed -> {
			if (!COMPLETING.equals(phase(appSession))) {
				return;
			}
			sipLogger.warning(failed, "AttendedTransfer - transferee and target could not be connected ("
					+ failed.getStatus() + "); returning the transferee to the pool");
			SettingsManager.createEvent("transferDeclined", failed);
			listener().transferDeclined(failed);
			SettingsManager.sendEvent(failed);
			SipSession c = session(appSession, TARGET_ATTR);
			if (live(c)) {
				clearExpectation(c, BYE);
				sendRequest(c.createRequest(BYE));
			}
			returnToPool(appSession);
		});
	}

	/// Carol hung up during the consultation: reconnect Alice and Bob.
	private void targetLeft(SipApplicationSession appSession, SipServletRequest carolBye)
			throws ServletException, IOException {
		appSession.setAttribute(PHASE_ATTR, RECONNECTING);
		SipSession alice = session(appSession, TRANSFEREE_ATTR);
		SipSession bob = session(appSession, TRANSFEROR_ATTR);
		sipLogger.fine(carolBye, "AttendedTransfer - target hung up during the consultation; reconnecting");

		declined(carolBye);

		expectRequest(bob, BYE, bye -> {
			sendResponse(bye.createResponse(200));
			if (RECONNECTING.equals(phase(appSession))) {
				returnToPool(appSession);
			}
		});

		connect(appSession, bob, alice, 0, bobOffer -> {
			clearExpectation(alice, BYE);
			clearExpectation(alice, INVITE);
			clearExpectation(bob, BYE);
			if (notifyTransferor) {
				notifyTransferor(appSession, TERMINATED_REJECTED, "SIP/2.0 487 Request Terminated");
			}
			finish(appSession);
		}, failed -> {
			if (!RECONNECTING.equals(phase(appSession))) {
				return;
			}
			sipLogger.warning(failed, "AttendedTransfer - transferee and transferor could not be reconnected ("
					+ failed.getStatus() + "); returning the transferee to the pool");
			SipSession b = session(appSession, TRANSFEROR_ATTR);
			if (live(b)) {
				clearExpectation(b, BYE);
				sendRequest(b.createRequest(BYE));
			}
			returnToPool(appSession);
		});
	}

	/// Alice is alone: Bob and Carol are both gone. She goes back to the
	/// destination of the call she placed, where a new Bob answers. If she
	/// was the one called, there is no such destination, and she is hung up.
	private void returnToPool(SipApplicationSession appSession) throws ServletException, IOException {
		if (RETURNING.equals(phase(appSession))) {
			return;
		}
		appSession.setAttribute(PHASE_ATTR, RETURNING);
		SipSession alice = session(appSession, TRANSFEREE_ATTR);
		if (!live(alice)) {
			finish(appSession);
			return;
		}

		SipServletRequest initial = (SipServletRequest) appSession.getAttribute(INITIAL_INVITE_ATTR);
		if (initial == null || !alice.getCallId().equals(initial.getCallId())) {
			sipLogger.warning(appSession, "AttendedTransfer - the transferee placed no call to return to; hanging up");
			clearExpectation(alice, BYE);
			clearExpectation(alice, INVITE);
			sendRequest(alice.createRequest(BYE));
			finish(appSession);
			return;
		}

		SipServletRequest invite = sipFactory.createRequest(appSession, INVITE, alice.getRemoteParty(),
				initial.getTo());
		invite.setRequestURI(initial.getRequestURI());
		preserveInviteHeaders(initial, invite);
		SipSession newBob = invite.getSession();
		sipLogger.fine(appSession, "AttendedTransfer - returning the transferee to " + initial.getRequestURI());

		expectRequest(alice, BYE, bye -> {
			sendResponse(bye.createResponse(200));
			if (newBob.getState() == SipSession.State.CONFIRMED) {
				sendRequest(newBob.createRequest(BYE));
			} else if (live(newBob)) {
				sendRequest(invite.createCancel());
			}
			finish(appSession);
		});

		sendRequest(invite, poolResponse -> {
			if (provisional(poolResponse)) {
				return;
			}
			SipSession a = session(appSession, TRANSFEREE_ATTR);
			if (failure(poolResponse)) {
				sipLogger.warning(poolResponse, "AttendedTransfer - the pool refused the transferee ("
						+ poolResponse.getStatus() + "); hanging up");
				if (live(a)) {
					clearExpectation(a, BYE);
					clearExpectation(a, INVITE);
					sendRequest(a.createRequest(BYE));
				}
				finish(appSession);
				return;
			}
			if (!live(a)) {
				sendRequest(parkedAck(poolResponse));
				sendRequest(newBob.createRequest(BYE));
				finish(appSession);
				return;
			}
			SipServletRequest toAlice = a.createRequest(INVITE);
			copyContent(poolResponse, toAlice);
			sendRequest(toAlice, aliceResponse -> {
				if (provisional(aliceResponse)) {
					return;
				}
				if (successful(aliceResponse)) {
					sendRequest(aliceResponse.createAck());
					sendRequest(copyContent(aliceResponse, poolResponse.createAck()));
					clearExpectation(a, BYE);
					clearExpectation(a, INVITE);
					linkBoth(a, newBob);
				} else {
					sipLogger.warning(aliceResponse, "AttendedTransfer - transferee refused the pool's offer ("
							+ aliceResponse.getStatus() + "); hanging up");
					sendRequest(parkedAck(poolResponse));
					sendRequest(newBob.createRequest(BYE));
					clearExpectation(a, BYE);
					sendRequest(a.createRequest(BYE));
				}
				finish(appSession);
			});
		});
	}

	/// Alice hangs up during the consultation or after it: Bob and Carol go
	/// too, there being no point in their talking once the customer has left.
	private void expectTransfereeBye(SipApplicationSession appSession) {
		SipSession alice = session(appSession, TRANSFEREE_ATTR);
		expectRequest(alice, BYE, bye -> {
			sendResponse(bye.createResponse(200));
			sipLogger.fine(bye, "AttendedTransfer - transferee hung up; hanging up transferor and target");
			appSession.setAttribute(PHASE_ATTR, RETURNING); // no recovery may start now
			for (String attr : new String[] { TRANSFEROR_ATTR, TARGET_ATTR }) {
				SipSession other = session(appSession, attr);
				if (live(other)) {
					clearExpectation(other, BYE);
					sendRequest(other.createRequest(BYE));
				}
			}
			finish(appSession);
			SettingsManager.createEvent("transferAbandoned", bye);
			listener().transferAbandoned(bye);
			SettingsManager.sendEvent(bye);
		});
	}

	/// Alice's own re-INVITEs (session refreshes) while she waits are answered
	/// with an inactive answer, so nothing she sends reaches Bob or Carol.
	private void parkTransfereeReinvites(SipApplicationSession appSession) {
		SipSession alice = session(appSession, TRANSFEREE_ATTR);
		expectRequest(alice, INVITE, reinvite -> {
			new CallflowHold().process(reinvite);
			String phase = phase(appSession);
			if (CONSULTING.equals(phase) || COMPLETING.equals(phase) || RECONNECTING.equals(phase)) {
				parkTransfereeReinvites(appSession);
			}
		});
	}

	/// Parks `dialog`: a re-INVITE with no offer, its offer answered
	/// `a=inactive` in the ACK. A glare is retried while the consultation lasts.
	private void park(SipApplicationSession appSession, SipSession dialog) throws ServletException, IOException {
		sendRequest(dialog.createRequest(INVITE), response -> {
			if (provisional(response)) {
				return;
			}
			if (successful(response)) {
				sendRequest(parkedAck(response));
			} else if (response.getStatus() == 491) {
				startTimer(appSession, GLARE_RETRY_MS, false, timer -> {
					if (CONSULTING.equals(phase(appSession)) && live(dialog)) {
						park(appSession, dialog);
					}
				});
			} else {
				sipLogger.warning(response, "AttendedTransfer - could not park the transferee ("
						+ response.getStatus() + ")");
			}
		});
	}

	/// Connects two dialogs (RFC 3725 flow I): a re-INVITE with no offer to
	/// `offerer`, its offer to `answerer` in a second re-INVITE, the answer
	/// back in the offerer's ACK. `connected` gets the offerer's 200; `failed`
	/// gets the response that refused, after one retry of a glare.
	private void connect(SipApplicationSession appSession, SipSession offerer, SipSession answerer, int attempt,
			Callback<SipServletResponse> connected, Callback<SipServletResponse> failed) throws ServletException, IOException {
		sendRequest(offerer.createRequest(INVITE), offer -> {
			if (provisional(offer)) {
				return;
			}
			if (!successful(offer) || offer.getContent() == null) {
				if (successful(offer)) {
					sendRequest(offer.createAck()); // a 200 without the offer it owed
				}
				retryOrFail(appSession, offerer, answerer, attempt, offer, connected, failed);
				return;
			}
			SipServletRequest toAnswerer = answerer.createRequest(INVITE);
			copyContent(offer, toAnswerer);
			sendRequest(toAnswerer, answer -> {
				if (provisional(answer)) {
					return;
				}
				if (successful(answer)) {
					sendRequest(answer.createAck());
					sendRequest(copyContent(answer, offer.createAck()));
					linkBoth(offerer, answerer);
					connected.accept(offer);
				} else {
					sendRequest(parkedAck(offer));
					retryOrFail(appSession, offerer, answerer, attempt, answer, connected, failed);
				}
			});
		});
	}

	private void retryOrFail(SipApplicationSession appSession, SipSession offerer, SipSession answerer,
			int attempt, SipServletResponse refusal, Callback<SipServletResponse> connected,
			Callback<SipServletResponse> failed) {
		if (refusal.getStatus() == 491 && attempt == 0 && live(offerer) && live(answerer)) {
			String phase = phase(appSession);
			startTimer(appSession, GLARE_RETRY_MS, false, timer -> {
				if (phase != null && phase.equals(phase(appSession)) && live(offerer) && live(answerer)) {
					connect(appSession, offerer, answerer, 1, connected, failed);
				}
			});
		} else {
			failed.accept(refusal);
		}
	}

	/// The ACK for a 200 whose offer this service answers `a=inactive`.
	private static SipServletRequest parkedAck(SipServletResponse offer) throws IOException {
		SipServletRequest ack = offer.createAck();
		String answer = CallflowHold.inactiveAnswerFor(offer);
		if (answer != null) {
			ack.setContent(answer.getBytes(StandardCharsets.UTF_8), SDP);
		}
		return ack;
	}

	/// A NOTIFY on Bob's REFER subscription, if he is still there to get it.
	private void notifyTransferor(SipApplicationSession appSession, String subscriptionState, String sipfrag) {
		SipSession bob = session(appSession, TRANSFEROR_ATTR);
		if (!live(bob)) {
			return;
		}
		try {
			SipServletRequest notify = bob.createRequest(NOTIFY);
			notify.setHeader(EVENT, "refer");
			notify.setHeader(SUBSCRIPTION_STATE, subscriptionState);
			notify.setContent(sipfrag.getBytes(StandardCharsets.UTF_8), SIPFRAG);
			sendRequest(notify);
		} catch (Exception e) {
			sipLogger.warning(appSession, "AttendedTransfer - could not NOTIFY the transferor: " + e.getMessage());
		}
	}

	/// The transfer is over, one way or another: drop its state and lift the
	/// transferee's one-transfer guard.
	void finish(SipApplicationSession appSession) {
		if (appSession == null || !appSession.isValid()) {
			return;
		}
		SipSession alice = session(appSession, TRANSFEREE_ATTR);
		if (alice != null && alice.isValid()) {
			setReferPending(alice, false);
		}
		for (String attr : new String[] { TRANSFEREE_ATTR, TRANSFEROR_ATTR, TARGET_ATTR, PHASE_ATTR,
				TRANSFEROR_GONE_ATTR }) {
			appSession.removeAttribute(attr);
		}
	}

	/// Whether an attended transfer is running in `appSession`.
	public static boolean inProgress(SipApplicationSession appSession) {
		return appSession != null && appSession.isValid() && appSession.getAttribute(PHASE_ATTR) != null;
	}

	/// `transferDeclined` for a transfer that a request ended (Carol's BYE):
	/// on the bus, and to an observer, since the v2 listener takes only a
	/// response.
	private void declined(SipServletRequest request) throws ServletException, IOException {
		SettingsManager.createEvent("transferDeclined", request);
		if (observer() != null) {
			observer().transferDeclined(request);
		}
		SettingsManager.sendEvent(request);
	}

	private TransferObserver observer() {
		TransferListener delegate = ((Outcomes) transferListener).delegate;
		return (delegate instanceof TransferObserver) ? (TransferObserver) delegate : null;
	}

	private TransferListener listener() {
		TransferListener delegate = ((Outcomes) transferListener).delegate;
		return (delegate != null) ? delegate : Outcomes.SILENT;
	}

	private static String phase(SipApplicationSession appSession) {
		return (appSession != null && appSession.isValid()) ? (String) appSession.getAttribute(PHASE_ATTR) : null;
	}

	private static SipSession session(SipApplicationSession appSession, String attr) {
		if (appSession == null || !appSession.isValid()) {
			return null;
		}
		String id = (String) appSession.getAttribute(attr);
		return (id != null) ? appSession.getSipSession(id) : null;
	}

	private static boolean live(SipSession session) {
		return session != null && session.isValid() && session.getState() != SipSession.State.TERMINATED;
	}

	private static void linkBoth(SipSession a, SipSession b) {
		linkSession(a, b);
		linkSession(b, a);
	}

	private static void clearExpectation(SipSession session, String method) {
		if (session != null && session.isValid()) {
			session.removeAttribute(REQUEST_CALLBACK_ + method);
		}
	}

	private static final String USER_AGENT = "userAgent";
	private static final String CALLEE = "callee";
	private static final String CALLER = "caller";
	private static final String X_PREVIOUS_DN = "X-Previous-DN";

	/// The listener the base class reports to. It passes every event on, and
	/// on the two that end a transfer before Carol answers (she declined, or
	/// Alice gave up) it finishes this one; a decline also owes Bob's
	/// subscription its failure NOTIFY. This class's own events go straight
	/// to the delegate.
	static final class Outcomes implements TransferListener {
		private static final long serialVersionUID = 1L;

		static final TransferListener SILENT = new Outcomes(null);

		final TransferListener delegate;
		AttendedTransfer owner;

		Outcomes(TransferListener delegate) {
			this.delegate = delegate;
		}

		@Override
		public void transferRequested(SipServletRequest inboundRefer) throws ServletException, IOException {
			if (delegate != null) {
				delegate.transferRequested(inboundRefer);
			}
		}

		@Override
		public void transferInitiated(SipServletRequest outboundRequest) throws ServletException, IOException {
			if (delegate != null) {
				delegate.transferInitiated(outboundRequest);
			}
		}

		@Override
		public void transferCompleted(SipServletResponse response) throws ServletException, IOException {
			if (delegate != null) {
				delegate.transferCompleted(response);
			}
		}

		@Override
		public void transferDeclined(SipServletResponse response) throws ServletException, IOException {
			if (owner != null) {
				SipApplicationSession appSession = response.getApplicationSession();
				if (owner.notifyTransferor) {
					owner.notifyTransferor(appSession, TERMINATED_REJECTED,
							"SIP/2.0 " + response.getStatus() + " " + response.getReasonPhrase());
				}
				owner.finish(appSession);
			}
			if (delegate != null) {
				delegate.transferDeclined(response);
			}
		}

		@Override
		public void transferAbandoned(SipServletRequest request) throws ServletException, IOException {
			if (owner != null) {
				owner.finish(request.getApplicationSession());
			}
			if (delegate != null) {
				delegate.transferAbandoned(request);
			}
		}
	}
}
