package org.vorpal.blade.framework.proxy.callflows;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.UAMode;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.proxy.ProxyListener;
import org.vorpal.blade.framework.proxy.ProxyTier;


public class ProxyInvite extends Callflow {
	private ProxyListener listener;
	private SipServletRequest aliceRequest;
	private SipServletResponse aliceResponse;

	private SipServletRequest bobRequest; // serves as a template, do not actually send;
	private SipServletResponse bobResponse;

	SipServletRequest proxyRequest = null;

//	private List<ProxyTier> proxyTiers;
	private ProxyTier proxyTier;

//  ServletTimer cannot be serialized	
//	private ServletTimer timer = null;
//	private String timerId = null;

	List<SipServletRequest> proxyRequests = new LinkedList<>();
	List<SipServletResponse> failedResponses = new LinkedList<>();

	public ProxyInvite(ProxyListener listener) {
		this.listener = listener;
//		this.proxyTiers = new LinkedList<>();
		proxyTier = new ProxyTier();
	}

	public void proxyOn(ProxyTier proxyTier) throws ServletException, IOException {
		if (proxyTier == null || proxyTier.getEndpoints().size() == 0) { // nothing left to proxy
			listener.proxyResponse(aliceResponse); // let the user modify the response headers
			sendResponse(aliceResponse);

		} else {

			bobRequest = createContinueRequest(aliceRequest); // create a fresh template request

			if (proxyTier.getMode().equals(ProxyTier.Mode.parallel)) {
				proxyParallel(proxyTier.getTimeout(), proxyTier.getEndpoints());
			} else {
				proxySerial(proxyTier.getTimeout(), proxyTier.getEndpoints());
			}
		}

	}

	public void proxyParallel(int timeout, List<URI> endpoints) throws IOException, ServletException {
		String timerId = null;

		if (timeout > 0) {
//			timer = scheduleTimer(bobRequest.getApplicationSession(), timeout, (timer) -> {
			timerId = scheduleTimer(bobRequest.getApplicationSession(), timeout, (timer) -> {
				// timeout occurred, cancel outstanding requests

				for (SipServletRequest pr : proxyRequests) {

					if (pr.getSession() != null && pr.getSession().isValid()
							&& pr.getSession().getState().equals(SipSession.State.EARLY)) {

						// unnecessary, container does not provide 200 OK to CANCEL
//						sendRequest(pr.createCancel(), (ok) -> {
//							// do nothing;
//						});
						sendRequest(pr.createCancel());

					}
				}
			});
		}

		SipServletRequest proxyRequest;
		for (URI uri : endpoints) {
			proxyRequest = createContinueRequest(bobRequest);
			proxyRequest.setRequestURI(uri);
			proxyRequests.add(proxyRequest);
			if (timerId != null) {
				proxyRequest.getSession().setAttribute("TIMER", timerId);
			}

			sendRequest(proxyRequest, (proxyResponse) -> {

				String _timerId = (String) proxyResponse.getSession().getAttribute("TIMER");

				if (successful(proxyResponse)) {

					proxyRequests.remove(proxyResponse.getRequest());

					// cancel timer
					if (_timerId != null) {
						ServletTimer t;
						t = proxyResponse.getApplicationSession().getTimer(_timerId);
						if (t != null) {
							t.cancel();
						}
					}

					// cancel outstanding requests
					for (SipServletRequest req : proxyRequests) {
						if (req.getSession() != null && req.getSession().isValid()
								&& req.getSession().getState().equals(State.EARLY)) {
							sendRequest(req.createCancel());
						}
					}

					aliceResponse = aliceRequest.createResponse(proxyResponse.getStatus());
					this.copyContentAndHeaders(proxyResponse, aliceResponse);
					listener.proxyResponse(aliceResponse); // successful proxy, do not attempt to proxy again

					linkSessions(proxyResponse.getSession(), aliceResponse.getSession());
					sendResponse(aliceResponse, (ack) -> {
						sendRequest(copyContentAndHeaders(ack, proxyResponse.createAck()));
					});

				} else if (failure(proxyResponse)) {

					// Remove from outstanding requests
					proxyResponse.getSession().removeAttribute("TIMER");
					failedResponses.add(proxyResponse);

					if (proxyRequests.size() == failedResponses.size()) {

						if (_timerId != null) {
							ServletTimer t = proxyResponse.getApplicationSession().getTimer(_timerId);
							if (t != null) {
								t.cancel();
							}
						}

						// reset for next attempt to proxy
						proxyRequests = new LinkedList<>();
						failedResponses = new LinkedList<>();

						// try to proxy again
						proxyTier = listener.proxyRequest(this.bobRequest); // attempt to proxy again
						if (proxyTier != null && proxyTier.getEndpoints().size() > 0) {
							bobRequest = createContinueRequest(aliceRequest); // create a new dummy template
							proxyOn(proxyTier); // you had all summer long, figure out what it is you do

						} else {
							aliceResponse = aliceRequest.createResponse(proxyResponse.getStatus());
							this.copyContentAndHeaders(proxyResponse, aliceResponse);
							sendResponse(aliceResponse);
						}

					}
				}

			});

		}

	}

	public void proxySerial(int timeout, List<URI> endpoints) throws IOException, ServletException {
		ProxyTier proxyTier;
		String timerId = null;

		if (endpoints.size() == 0) { // nothing left to proxy

			proxyTier = listener.proxyRequest(this.bobRequest); // attempt to proxy again

			if (proxyTier != null && proxyTier.getEndpoints().size() > 0) {
				this.bobRequest = createContinueRequest(aliceRequest);

				if (proxyTier.getMode().equals(ProxyTier.Mode.parallel)) {
					proxyParallel(timeout, proxyTier.getEndpoints());
				} else {
					proxySerial(timeout, proxyTier.getEndpoints());
				}
			} else { // nothing left to proxy
				sendResponse(aliceRequest.createResponse(487));
				// proxySerial(timeout, endpoints);

			}
		} else { // do the serial proxy
			URI endpoint = endpoints.remove(0);

			if (timeout > 0) {
//				timer = scheduleTimer(bobRequest.getApplicationSession(), timeout, (timer) -> {
				timerId = scheduleTimer(bobRequest.getApplicationSession(), timeout, (timer) -> {
					// timeout occurred, cancel outstanding requests

					// jwm-bug where unnecessary cancels are being sent
					if (proxyRequest != null && proxyRequest.getSession() != null && proxyRequest.getSession().isValid()
							&& proxyRequest.getSession().getState().equals(SipSession.State.EARLY)) {
						sendRequest(proxyRequest.createCancel());
					}

				});
			}

			proxyRequest = createContinueRequest(bobRequest);
			proxyRequest.setRequestURI(endpoint);
			if (timerId != null) {
				proxyRequest.getSession().setAttribute("TIMER", timerId);
			}

			sendRequest(proxyRequest, (proxyResponse) -> {
				String _timerId = null;
				if (false == provisional(proxyResponse)) {
					// Cancel the timer for anything other than a provisional response
					_timerId = (String) proxyResponse.getSession().getAttribute("TIMER");
					if (_timerId != null) {
						ServletTimer t = proxyResponse.getApplicationSession().getTimer(_timerId);
						if (t != null) {
							t.cancel();
						}
						proxyResponse.getSession().removeAttribute("TIMER");
					}
				}

				if (successful(proxyResponse)) {
					SipServletResponse tmpAliceResponse = aliceRequest.createResponse(proxyResponse.getStatus());
					copyContentAndHeaders(proxyResponse, tmpAliceResponse);
					listener.proxyResponse(tmpAliceResponse); // let the user manipulate the headers if necessary;
					linkSessions(proxyResponse.getSession(), tmpAliceResponse.getSession());
					sendResponse(tmpAliceResponse, (tmpAliceAck) -> {
						SipServletRequest tmpBobAck = proxyResponse.createAck();
						copyContentAndHeaders(tmpAliceAck, tmpBobAck);
						sendRequest(tmpBobAck);
					});
				} else {
					if (this.failure(proxyResponse)) {
						// try the next endpoint, using regression
						proxySerial(timeout, endpoints);
					}
				}
			});

		}

	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		this.aliceRequest = request;

		ProxyTier proxyTier = listener.proxyRequest(request);

		if (proxyTier == null || proxyTier.getEndpoints().size() == 0) {
			aliceResponse = request.createResponse(403);
			listener.proxyResponse(aliceResponse);
			sendResponse(aliceResponse);
		} else {
			proxyOn(proxyTier);
		}
	}

	public void cancelOutstandingRequests(SipApplicationSession appSession, String timerId)
			throws ServletException, IOException {
		SipSession sipSession;
		String tid;
		Iterator<SipSession> itr = (Iterator<SipSession>) appSession.getSessions();
		while (itr.hasNext()) {
			sipSession = itr.next();
			tid = (String) sipSession.getAttribute("TIMER");
			if (tid.equals(timerId)) {
				sipSession.removeAttribute("TIMER");
				SipServletRequest activeInvite = sipSession.getActiveInvite(UAMode.UAC);
				if (activeInvite != null && activeInvite.getSession() != null && activeInvite.getSession().isValid()
						&& activeInvite.getSession().getState().equals(State.EARLY)) {
					sendRequest(activeInvite.createCancel());
				}
			}
		}
	}

}
