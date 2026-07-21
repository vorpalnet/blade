package org.vorpal.blade.proto.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/// The `register-digest` registrar: originates a REGISTER for one {@link VirtualGateway},
/// handles the 401/407 digest challenge (`addAuthHeader`), and keeps the registration alive
/// with a recurring SIP servlet timer (re‑REGISTER before Expires lapses). One live instance
/// per virtual gateway.
///
/// State is minimal and serializable (config refs + the outbound address + ids), so the
/// response/timer continuations ride the (distributable) app session through failover.
public class RegisterCallflow extends TrunkRegistrar {
	private static final long serialVersionUID = 1L;

	/// Re‑REGISTER this many seconds before the lifetime lapses.
	private static final long REFRESH_MARGIN_SECONDS = 30;

	private final VirtualGateway gateway;
	private final RegisterDigestStyle style;
	private InetSocketAddress outboundInterface; // may be null → container default
	private String sasId;
	private String timerId;

	public RegisterCallflow(VirtualGateway gateway, RegisterDigestStyle style) {
		this.gateway = gateway;
		this.style = style;
	}

	@Override
	public void start(InetSocketAddress outboundInterface) throws ServletException, IOException {
		this.outboundInterface = outboundInterface;
		SipApplicationSession sas = getSipFactory().createApplicationSession();
		this.sasId = sas.getId();
		sendRegister(sas, style.getExpires());
	}

	@Override
	public void stop() {
		try {
			SipApplicationSession sas = (sasId != null) ? getSipUtil().getApplicationSessionById(sasId) : null;
			if (sas != null) {
				if (timerId != null) {
					stopTimer(sas, timerId);
					timerId = null;
				}
				sendRegister(sas, 0); // Expires:0 → de‑register
			}
		} catch (Exception e) {
			sipLogger.warning("gateway " + name() + ": de‑register failed: " + e.getMessage());
		}
	}

	private void sendRegister(SipApplicationSession sas, int expires) throws ServletException, IOException {
		String aor = "sip:" + style.getUserId() + "@" + gateway.getRegistrarDomain();
		Address address = getSipFactory().createAddress("\"" + name() + "\" <" + aor + ">");
		SipServletRequest register = getSipFactory().createRequest(sas, "REGISTER", address, address);
		register.setRequestURI(getSipFactory().createURI(
				"sip:" + gateway.getRegistrarDomain() + ";transport=" + gateway.getTransport()));
		bindOutbound(register);
		register.setExpires(expires);
		register.setHeader("Allow", style.getAllow());
		register.setHeader("User-Agent", style.getUserAgent());
		sendRequest(register, this::onResponse);
	}

	private void bindOutbound(SipServletRequest register) {
		if (outboundInterface != null) {
			try {
				register.getSession().setOutboundInterface(outboundInterface);
			} catch (Exception e) {
				sipLogger.warning("gateway " + name() + ": setOutboundInterface(" + outboundInterface + ") failed: "
						+ e.getMessage());
			}
		}
	}

	private void onResponse(SipServletResponse response) throws ServletException, IOException {
		if (provisional(response)) {
			return;
		}
		int status = response.getStatus();
		SipApplicationSession sas = response.getApplicationSession();

		if (status == 401 || status == 407) {
			SipServletRequest challenged = response.getRequest();
			boolean alreadyAuthed = challenged.getHeader("Authorization") != null
					|| challenged.getHeader("Proxy-Authorization") != null;
			if (alreadyAuthed) {
				// A challenge to an already‑authenticated REGISTER means bad creds — don't loop.
				sipLogger.severe("gateway " + name() + ": REGISTER auth rejected (" + status + ")");
				return;
			}
			SipServletRequest retry = createRequest(response, "REGISTER");
			retry.setRequestURI(getSipFactory().createURI(
					"sip:" + gateway.getRegistrarDomain() + ";transport=" + gateway.getTransport()));
			bindOutbound(retry);
			retry.addAuthHeader(response, style.getAuthName(), style.getPassword());
			retry.setExpires(challenged.getExpires()); // preserve 0 (de‑register) or the configured value
			retry.setHeader("Allow", style.getAllow());
			retry.setHeader("User-Agent", style.getUserAgent());
			sendRequest(retry, this::onResponse);
			return;
		}

		if (successful(response)) {
			int requested = response.getRequest().getExpires();
			if (requested == 0) {
				sipLogger.info("gateway " + name() + ": de‑registered (" + status + ")");
				return;
			}
			if (timerId == null && sas != null) {
				long periodMs = Math.max(30, requested - REFRESH_MARGIN_SECONDS) * 1000L;
				timerId = startTimer(sas, periodMs, periodMs, false, true, this::onTimer);
			}
			sipLogger.info("gateway " + name() + ": REGISTER " + status + " (expires " + requested + "s)");
			return;
		}

		sipLogger.severe("gateway " + name() + ": REGISTER failed " + status + " " + response.getReasonPhrase());
	}

	private void onTimer(ServletTimer timer) throws ServletException, IOException {
		SipApplicationSession sas = timer.getApplicationSession();
		if (sas != null && sas.isValid()) {
			sendRegister(sas, style.getExpires()); // refresh; re‑auths via the 401 path
		}
	}

	private String name() {
		return gateway.getName() != null ? gateway.getName() : gateway.getRegistrarDomain();
	}
}
