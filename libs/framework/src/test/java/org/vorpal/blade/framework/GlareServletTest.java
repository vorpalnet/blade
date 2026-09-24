package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipErrorEvent;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession.State;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow.GlareState;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;

/// The glare guard on the receiving side, `AsyncSipServlet.doRequest` and `doResponse`, run on
/// detached objects with no container.
///
/// The rule it enforces is one transaction at a time per dialog: while a transaction is open
/// (`PROTECT`) a new request is refused `491`; between our 2xx to an INVITE and its ACK (`QUEUE`) a
/// request is parked and replayed once the ACK arrives. A PRACK is the exception: it belongs to the
/// INVITE transaction still open, so it always arrives while the dialog is protected, and neither it
/// nor its 200 may open or close protection.
///
/// A servlet can be built in a unit test once `javax/servlet/LocalStrings.properties` is on the test
/// classpath: `GenericServlet` loads that bundle in a static initializer, and the servlet API on the
/// compile classpath omits it.
@DisplayName("glare guard, receiving side")
class GlareServletTest {

	/// The methods that got past the guard to the application.
	final List<String> reached = new ArrayList<>();

	/// Whether the application answers each request `200` before returning from `process`.
	boolean answer;

	final AsyncSipServlet servlet = new AsyncSipServlet() {
		private static final long serialVersionUID = 1L;

		@Override
		protected void servletCreated(SipServletContextEvent event) {
		}

		@Override
		protected void servletDestroyed(SipServletContextEvent event) {
		}

		@Override
		protected Callflow chooseCallflow(SipServletRequest request) {
			return new Callflow() {
				private static final long serialVersionUID = 1L;

				@Override
				public void process(SipServletRequest r) throws ServletException, IOException {
					reached.add(r.getMethod());
					if (answer && !"ACK".equals(r.getMethod())) {
						sendResponse(r.createResponse(200));
					}
				}
			};
		}
	};

	DetachedApplicationSession app;
	DetachedSipSession dialog;

	@BeforeEach
	void setUp() {
		CapturingLogger log = new CapturingLogger();
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipLogger(log);
		AsyncSipServlet.setSipLogger(log);
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		AsyncSipServlet.replayer = servlet; // what servletInitialized does in the container
		app = new DetachedApplicationSession("glare");
		dialog = new DetachedSipSession(app);
	}

	@AfterEach
	void tearDown() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		AsyncSipServlet.setSipLogger(null);
		Callflow.setSipUtil(null);
		AsyncSipServlet.replayer = null;
	}

	/// The status of every response the framework created for a request, in order.
	final List<String> answered = new ArrayList<>();

	private DetachedRequest inDialog(String method) throws Exception {
		return inDialog(dialog, method);
	}

	private DetachedRequest inDialog(DetachedSipSession session, String method) throws Exception {
		DetachedRequest request = new DetachedRequest(app, method) {
			@Override
			public SipServletResponse createResponse(int status) {
				answered.add(method + " " + status);
				return super.createResponse(status);
			}
		};
		request.setSession(session);
		request.setInitial(false);
		return request;
	}

	private void receive(String method) throws Exception {
		servlet.doRequest(inDialog(method));
	}

	private void respond(String method, int status) throws Exception {
		servlet.doResponse(new DetachedResponse(inDialog(method), status));
	}

	/// Send a REFER on this dialog the way an application does, through `Callflow.sendRequest`.
	private void sendRefer() throws Exception {
		DetachedRequest refer = inDialog("REFER");
		new Callflow() {
			private static final long serialVersionUID = 1L;

			@Override
			public void process(SipServletRequest r) throws ServletException, IOException {
				sendRequest(r, (response) -> {
				});
			}
		}.process(refer);
	}

	/// A refer-event NOTIFY carrying the given sipfrag status line.
	private DetachedRequest notify(String sipfrag, String subscriptionState) throws Exception {
		DetachedRequest notify = inDialog("NOTIFY");
		notify.setHeader("Event", "refer");
		notify.setHeader("Subscription-State", subscriptionState);
		notify.setContent(sipfrag, "message/sipfrag");
		return notify;
	}

	private DetachedRequest progress() throws Exception {
		return notify("SIP/2.0 100 Trying", "active;expires=60");
	}

	private DetachedRequest finished() throws Exception {
		return notify("SIP/2.0 200 OK", "terminated;reason=noresource");
	}

	private GlareState state() {
		return Callflow.getGlareState(dialog);
	}

	// ---- one transaction at a time

	@Test
	void aRequestOnAQuietDialogIsHandledAndOpensATransaction() throws Exception {
		receive("INFO");
		assertEquals(List.of("INFO"), reached);
		assertEquals(GlareState.PROTECT, state());
	}

	@Test
	void aSecondRequestWhileOneIsOpenIsRefused() throws Exception {
		Callflow.setGlareState(dialog, GlareState.PROTECT);
		receive("INFO");
		assertTrue(reached.isEmpty(), "answered 491, never handed to the application");
		assertEquals(GlareState.PROTECT, state());
	}

	@Test
	void crossingInvitesAreRefused() throws Exception {
		Callflow.setGlareState(dialog, GlareState.PROTECT);
		receive("INVITE");
		assertTrue(reached.isEmpty(), "RFC 3261 section 14.2: 491 to the second INVITE");
	}

	@Test
	void byeAlwaysGetsThroughAndEndsProtection() throws Exception {
		Callflow.setGlareState(dialog, GlareState.PROTECT);
		receive("BYE");
		assertEquals(List.of("BYE"), reached);
		assertEquals(GlareState.ALLOW, state());
	}

	// ---- PRACK (RFC 3262)

	@Test
	void aPrackGetsThroughWhileItsInviteIsOpen() throws Exception {
		Callflow.setGlareState(dialog, GlareState.PROTECT);
		receive("PRACK");
		assertEquals(List.of("PRACK"), reached, "a 491 here leaves the reliable provisional unacknowledged");
		assertEquals(GlareState.PROTECT, state(), "the INVITE is still open");
	}

	@Test
	void aPrackOnAQuietDialogOpensNothing() throws Exception {
		receive("PRACK");
		assertEquals(List.of("PRACK"), reached);
		assertEquals(GlareState.ALLOW, state());
	}

	@Test
	void theAnswerToOurPrackKeepsTheInviteProtected() throws Exception {
		Callflow.setGlareState(dialog, GlareState.PROTECT);
		respond("PRACK", 200);
		assertEquals(GlareState.PROTECT, state(), "our INVITE is still open until its own final response");
	}

	// ---- responses to our own requests

	@Test
	void aProvisionalKeepsTheTransactionOpen() throws Exception {
		Callflow.setGlareState(dialog, GlareState.PROTECT);
		respond("INVITE", 183);
		assertEquals(GlareState.PROTECT, state());
	}

	@Test
	void aFinalResponseToOurInviteEndsProtection() throws Exception {
		Callflow.setGlareState(dialog, GlareState.PROTECT);
		respond("INVITE", 200);
		assertEquals(GlareState.ALLOW, state());
	}

	// ---- between our 2xx and its ACK

	@Test
	void aRequestBeforeTheAckIsParkedAndReplayedAfterIt() throws Exception {
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		receive("INFO");
		assertTrue(reached.isEmpty(), "parked, not refused");
		receive("ACK");
		assertEquals(List.of("ACK", "INFO"), reached, "the ACK opens the dialog and the parked INFO follows");
	}

	@Test
	void parkedRequestsReplayOneTransactionAtATime() throws Exception {
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		receive("INFO");
		receive("INFO");
		receive("ACK");
		assertEquals(List.of("ACK", "INFO"), reached, "the second waits for the first to be answered");
		assertEquals(GlareState.PROTECT, state());
	}

	@Test
	void theParkingLotIsBounded() throws Exception {
		answer = true;
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		for (int i = 0; i < AsyncSipServlet.MAX_GLARE_QUEUE + 1; i++) {
			receive("INFO");
		}
		receive("ACK");
		assertEquals(1 + AsyncSipServlet.MAX_GLARE_QUEUE, reached.size(),
				"the ACK and the parked requests; the one past the cap was refused");
		assertFalse(reached.size() > 1 + AsyncSipServlet.MAX_GLARE_QUEUE);
	}

	// ---- transfers (RFC 3515): one at a time per dialog

	@Test
	void aNotifyThatOvertakesTheReferAcceptanceGetsThrough() throws Exception {
		sendRefer();
		servlet.doRequest(progress());
		assertEquals(List.of("NOTIFY"), reached, "RFC 3515 section 2.4.4: must be prepared for it");
		assertEquals(GlareState.PROTECT, state(), "our REFER is still open");
	}

	@Test
	void theTransferOutlivesTheReferTransaction() throws Exception {
		sendRefer();
		respond("REFER", 202);
		assertEquals(GlareState.ALLOW, state(), "the REFER itself is done");
		assertTrue(Callflow.isReferPending(dialog), "the transfer is not; TransferAPI refuses a second one");
	}

	@Test
	void theDialogCarriesOnWhileTheTargetRings() throws Exception {
		sendRefer();
		respond("REFER", 202);
		servlet.doRequest(progress());
		receive("UPDATE");
		assertEquals(List.of("NOTIFY", "UPDATE"), reached, "a session refresh is not a second transfer");
	}

	@Test
	void aSecondReferDuringATransferIsRefused() throws Exception {
		receive("REFER");
		assertEquals(List.of("REFER"), reached);
		Callflow.setGlareState(dialog, GlareState.ALLOW); // the transferor answered our first NOTIFY
		receive("REFER");
		assertEquals(List.of("REFER"), reached, "the second REFER was answered 491");
	}

	@Test
	void aReferBeforeTheFirstIsAnsweredIsRefused() throws Exception {
		receive("REFER");
		receive("REFER");
		assertEquals(List.of("REFER"), reached);
	}

	@Test
	void theFinalNotifyEndsTheTransfer() throws Exception {
		sendRefer();
		respond("REFER", 202);
		servlet.doRequest(finished());
		assertFalse(Callflow.isReferPending(dialog));
		receive("REFER");
		assertEquals(List.of("NOTIFY", "REFER"), reached, "the next transfer is welcome");
	}

	@Test
	void aTerminatedSubscriptionEndsTheTransferWhateverTheSipfrag() throws Exception {
		Callflow.setReferPending(dialog, true);
		servlet.doRequest(notify("SIP/2.0 180 Ringing", "terminated;reason=timeout"));
		assertFalse(Callflow.isReferPending(dialog));
	}

	@Test
	void aNotifyForAnotherEventNeverEndsATransfer() throws Exception {
		Callflow.setReferPending(dialog, true);
		DetachedRequest presence = inDialog("NOTIFY");
		presence.setHeader("Event", "presence");
		presence.setHeader("Subscription-State", "terminated");
		servlet.doRequest(presence);
		assertTrue(Callflow.isReferPending(dialog));
	}

	@Test
	void aRefusedReferEndsTheTransferBeforeItStarts() throws Exception {
		sendRefer();
		respond("REFER", 603);
		assertEquals(GlareState.ALLOW, state());
		assertFalse(Callflow.isReferPending(dialog));
	}

	@Test
	void refusingAReferWeReceivedReleasesTheDialog() throws Exception {
		receive("REFER");
		new Callflow() {
			private static final long serialVersionUID = 1L;

			@Override
			public void process(SipServletRequest r) throws ServletException, IOException {
			}
		}.sendResponse(inDialog("REFER").createResponse(403));
		assertEquals(GlareState.ALLOW, state(), "left in PROTECT, every later request would get 491");
		assertFalse(Callflow.isReferPending(dialog));
	}

	@Test
	void ourFinalNotifyEndsATransferWeAccepted() throws Exception {
		receive("REFER");
		new Callflow() {
			private static final long serialVersionUID = 1L;

			@Override
			public void process(SipServletRequest r) throws ServletException, IOException {
				sendRequest(r);
			}
		}.process(finished());
		assertFalse(Callflow.isReferPending(dialog));
	}

	// ---- a BYE ends the dialog: parked requests are answered, never replayed (RFC 3261 section 15.1.2)

	@Test
	void aByeAnswersWhatIsParkedInsteadOfReplayingIt() throws Exception {
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		receive("INFO");
		receive("BYE");
		assertEquals(List.of("BYE"), reached, "the application never sees the INFO after the call is gone");
		assertEquals(List.of("INFO 487"), answered);
		assertEquals(GlareState.ALLOW, state());
	}

	@Test
	void ourByeAnswersWhatIsParkedAndALateAckReplaysNothing() throws Exception {
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		receive("INFO");
		receive("UPDATE");
		new Callflow() {
			private static final long serialVersionUID = 1L;

			@Override
			public void process(SipServletRequest r) throws ServletException, IOException {
				sendRequest(r);
			}
		}.process(inDialog("BYE"));
		assertEquals(List.of("INFO 487", "UPDATE 487"), answered);
		receive("ACK");
		assertEquals(List.of("ACK"), reached);
	}

	@Test
	void aByeOnAnEmptyQueueAnswersNothing() throws Exception {
		receive("BYE");
		assertTrue(answered.isEmpty());
	}

	// ---- our INVITE against one in progress (RFC 3261 section 14.1)

	/// Responses our own INVITE's callback received.
	final List<SipServletResponse> ourResponses = new ArrayList<>();

	private void sendInvite(DetachedRequest invite) throws Exception {
		new Callflow() {
			private static final long serialVersionUID = 1L;

			@Override
			public void process(SipServletRequest r) throws ServletException, IOException {
				sendRequest(r, (response) -> ourResponses.add(response));
			}
		}.process(invite);
	}

	@Test
	void ourInviteBeforeTheirAckIsRefusedLocally() throws Exception {
		Callflow.setGlareState(dialog, GlareState.QUEUE); // we answered their re-INVITE 200, no ACK yet
		sendInvite(inDialog("INVITE"));
		assertEquals(1, ourResponses.size());
		assertEquals(491, ourResponses.get(0).getStatus());
		assertEquals(GlareState.QUEUE, state(), "their INVITE still owns the dialog");
	}

	@Test
	void ourInviteWhileTheirsIsUnansweredIsRefusedLocally() throws Exception {
		receive("INVITE");
		sendInvite(inDialog("INVITE"));
		assertEquals(491, ourResponses.get(0).getStatus());
		assertEquals(GlareState.PROTECT, state());
	}

	@Test
	void onceTheirAckArrivesOurInviteGoesAndACrossingOneIsRefused() throws Exception {
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		sendInvite(inDialog("INVITE")); // refused locally, never opened
		receive("ACK");
		sendInvite(inDialog("INVITE")); // now it goes
		assertEquals(GlareState.PROTECT, state());
		receive("INVITE");
		assertEquals(List.of("ACK"), reached, "their crossing INVITE is answered 491, as section 14.2 says");
		assertEquals(List.of("INVITE 491"), answered);
	}

	@Test
	void ourInviteOnAQuietDialogIsSent() throws Exception {
		sendInvite(inDialog("INVITE"));
		assertTrue(ourResponses.isEmpty(), "sent; the answer comes from the peer");
		assertEquals(GlareState.PROTECT, state());
	}

	@Test
	void theLocal491CarriesNothingOfTheRefusedInvite() throws Exception {
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		DetachedRequest invite = inDialog("INVITE");
		invite.setHeader("Session-Expires", "1800;refresher=uac");
		invite.setContent("v=0", "application/sdp");
		sendInvite(invite);
		SipServletResponse pending = ourResponses.get(0);
		assertEquals(null, pending.getHeader("Session-Expires"));
		assertEquals(null, pending.getContent());
	}

	@Test
	void aB2buaRelaysTheLocal491ToTheLegThatAsked() throws Exception {
		DetachedSipSession bob = new DetachedSipSession(app);
		Callflow.linkSession(bob, dialog); // Alice's dialog -> Bob's
		Callflow.setGlareState(bob, GlareState.QUEUE); // Bob's own re-INVITE awaits our ACK
		new org.vorpal.blade.framework.v2.b2bua.Reinvite(null).process(inDialog("INVITE")); // Alice re-INVITEs
		assertEquals(List.of("INVITE 491"), answered, "Alice is told 491, and retries later");
		assertEquals(GlareState.QUEUE, Callflow.getGlareState(bob));
	}

	// ---- our UPDATE carries an offer like our INVITE (RFC 3311 section 5.2)

	@Test
	void anUpdateThatCrossesOursIsRefused() throws Exception {
		sendInvite(inDialog("UPDATE"));
		assertEquals(GlareState.PROTECT, state(), "our offer is outstanding");
		receive("UPDATE");
		assertTrue(reached.isEmpty());
		assertEquals(List.of("UPDATE 491"), answered);
	}

	@Test
	void ourReinviteWaitsForOurUpdatesAnswer() throws Exception {
		sendInvite(inDialog("UPDATE"));
		sendInvite(inDialog("INVITE"));
		assertEquals(1, ourResponses.size());
		assertEquals(491, ourResponses.get(0).getStatus());
	}

	@Test
	void ourUpdateWhileTheirInviteIsUnansweredIsRefusedLocally() throws Exception {
		receive("INVITE");
		sendInvite(inDialog("UPDATE"));
		assertEquals(491, ourResponses.get(0).getStatus());
		assertEquals(GlareState.PROTECT, state());
	}

	@Test
	void theAnswerToOurUpdateReleasesTheDialog() throws Exception {
		sendInvite(inDialog("UPDATE"));
		respond("UPDATE", 200);
		assertEquals(GlareState.ALLOW, state());
		receive("UPDATE");
		assertEquals(List.of("UPDATE"), reached);
	}

	@Test
	void aRelayedUpdateOntoABusyLegIsRefusedToTheLegThatAsked() throws Exception {
		DetachedSipSession bob = new DetachedSipSession(app);
		Callflow.linkSession(bob, dialog); // Alice's dialog -> Bob's
		Callflow.setGlareState(bob, GlareState.PROTECT); // Bob's own UPDATE is open on his leg
		new org.vorpal.blade.framework.v2.b2bua.Passthru(null).process(inDialog("UPDATE")); // Alice's UPDATE
		assertEquals(List.of("UPDATE 491"), answered);
	}

	// ---- the far end never confirms (the container's SipErrorListener callbacks)

	/// A dialog that records the requests the framework creates on it, which is what it sends.
	final List<String> created = new ArrayList<>();

	private DetachedSipSession leg(String name, State state) {
		DetachedSipSession leg = new DetachedSipSession(app) {
			@Override
			public SipServletRequest createRequest(String method) {
				created.add(name + " " + method);
				return super.createRequest(method);
			}
		};
		leg.setState(state);
		return leg;
	}

	@Test
	void noAckEndsBothLegsAndAnswersWhatIsParked() throws Exception {
		DetachedSipSession alice = leg("alice", State.CONFIRMED);
		DetachedSipSession bob = leg("bob", State.CONFIRMED);
		Callflow.linkSession(bob, alice);
		Callflow.setGlareState(alice, GlareState.QUEUE);
		servlet.doRequest(inDialog(alice, "INFO")); // parked, waiting for the ACK that never comes

		servlet.noAckReceived(new SipErrorEvent(inDialog(alice, "INVITE"), null));

		assertEquals(List.of("alice BYE", "bob BYE"), created, "RFC 3261 section 13.3.1.4");
		assertEquals(List.of("INFO 487"), answered);
		assertEquals(GlareState.ALLOW, Callflow.getGlareState(alice));
		assertTrue(reached.isEmpty());
	}

	@Test
	void noAckOnALoneDialogEndsJustThatDialog() throws Exception {
		DetachedSipSession alice = leg("alice", State.CONFIRMED);
		Callflow.setGlareState(alice, GlareState.QUEUE);
		servlet.noAckReceived(new SipErrorEvent(inDialog(alice, "INVITE"), null));
		assertEquals(List.of("alice BYE"), created);
		assertEquals(GlareState.ALLOW, Callflow.getGlareState(alice));
	}

	@Test
	void noPrackRejectsTheInviteAndCancelsTheRingingLeg() throws Exception {
		DetachedSipSession alice = leg("alice", State.EARLY);
		DetachedSipSession bob = leg("bob", State.EARLY);
		Callflow.linkSession(bob, alice);
		List<String> cancelled = new ArrayList<>();
		DetachedRequest bobInvite = new DetachedRequest(app, "INVITE") {
			@Override
			public boolean isCommitted() {
				return true; // sent, and ringing
			}

			@Override
			public SipServletRequest createCancel() {
				cancelled.add("bob CANCEL");
				return super.createCancel();
			}
		};
		bobInvite.setSession(bob);
		bob.setActiveInvite(bobInvite);

		servlet.noPrackReceived(new SipErrorEvent(inDialog(alice, "INVITE"), null));

		assertEquals(List.of("INVITE 500"), answered, "RFC 3262 section 3");
		assertEquals(List.of("bob CANCEL"), cancelled);
		assertTrue(created.isEmpty(), "no BYE on a dialog that never confirmed");
	}

	@Test
	void noNotifyIsOnlyLogged() throws Exception {
		servlet.noNotifyReceived(new SipErrorEvent(inDialog("REFER"), null));
		assertTrue(answered.isEmpty());
	}

	// ---- an answer sent later still releases what is parked

	@Test
	void aRelayedAnswerReplaysTheNextParkedRequest() throws Exception {
		List<SipServletRequest> toBob = new ArrayList<>();
		DetachedSipSession bob = new DetachedSipSession(app) {
			@Override
			public SipServletRequest createRequest(String method) {
				DetachedRequest relayed = (DetachedRequest) super.createRequest(method);
				relayed.setSession(this);
				toBob.add(relayed);
				return relayed;
			}
		};
		Callflow.linkSession(bob, dialog); // Alice's dialog -> Bob's
		AsyncSipServlet b2bua = new AsyncSipServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void servletCreated(SipServletContextEvent event) {
			}

			@Override
			protected void servletDestroyed(SipServletContextEvent event) {
			}

			@Override
			protected Callflow chooseCallflow(SipServletRequest request) {
				return "ACK".equals(request.getMethod()) ? null
						: new org.vorpal.blade.framework.v2.b2bua.Passthru(null);
			}
		};
		AsyncSipServlet.replayer = b2bua;

		Callflow.setGlareState(dialog, GlareState.QUEUE);
		b2bua.doRequest(inDialog("INFO"));
		b2bua.doRequest(inDialog("MESSAGE"));
		b2bua.doRequest(inDialog("ACK"));
		assertEquals(1, toBob.size(), "the INFO is relayed; the MESSAGE waits for its answer");

		b2bua.doResponse(new DetachedResponse(toBob.get(0), 200)); // Bob answers the INFO
		assertEquals(List.of("INFO 200"), answered);
		assertEquals(2, toBob.size(), "relaying Bob's 200 to Alice releases her dialog");
		assertEquals("MESSAGE", toBob.get(1).getMethod());

		b2bua.doResponse(new DetachedResponse(toBob.get(1), 200));
		assertEquals(List.of("INFO 200", "MESSAGE 200"), answered);
		assertEquals(GlareState.ALLOW, state());
	}

	@Test
	void aRequestArrivingBehindParkedOnesWaitsItsTurn() throws Exception {
		answer = true;
		Callflow.setGlareState(dialog, GlareState.QUEUE);
		receive("INFO");
		receive("MESSAGE");
		Callflow.setGlareState(dialog, GlareState.ALLOW); // released, not yet replayed
		receive("OPTIONS");
		assertEquals(List.of("INFO", "MESSAGE", "OPTIONS"), reached);
	}
}
