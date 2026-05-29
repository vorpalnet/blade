package org.vorpal.blade.test.uas.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

/// Transfer (REFER) test, in endpoint mode. Triggered when the initial INVITE
/// carries a `refer` Request-URI parameter (see
/// [UasServlet][org.vorpal.blade.test.uas.UasServlet]).
///
/// It answers the INVITE `200 OK`, then sends a `REFER` whose `Refer-To` is the
/// address from the `refer` parameter — with a `status` parameter appended
/// (default `200`) so the transfer target can be told what to respond. It then
/// drives the implicit-subscription NOTIFY handshake and, on a successful
/// transfer outcome (a `200` in the final NOTIFY's `message/sipfrag` body),
/// sends `BYE` to tear down the original dialog.
///
/// Example: `INVITE sip:bob@uas.test;refer=sip:carol@uas.test;status=200`
///
/// ```text
///  TestUAC             Transfer            TestRefer          TestInvite
///  transferee                              transferor         target
/// ----------           --------            ---------          ----------
///     |                   |                   |                   |
///     | INVITE            |                   |                   |
///     |------------------>|                   |                   |
///     |                   | INVITE            |                   |
///     |                   |------------------>|                   |
///     |                   |            200 OK |                   |
///     |                   |<------------------|                   |
///     |            200 OK |                   |                   |
///     |<------------------|                   |                   |
///     | ACK               |                   |                   |
///     |------------------>|                   |                   |
///     |                   | ACK               |                   |
///     |                   |------------------>|                   |
///     |                   |             REFER |                   |
///     |                   |<------------------|                   |
///     |                   | 202 Accepted      |                   |
///     |                   |------------------>|                   |
///     |                   | NOTIFY            |                   |
///     |                   |------------------>|                   |      ; SIP/2.0 100 Trying
///     |                   |            200 OK |                   |
///     |                   |<------------------|                   |
///     |                   | INVITE            |                   |
///     |                   |-------------------------------------->|
///     |                   |                   |         500 Error |
///     |                   |<--------------------------------------|
///     |                   | NOTIFY            |                   |
///     |                   |------------------>|                   |      ; SIP/2.0 500 Server Internal Error
///     |                   |            200 OK |                   |
///     |                   |<------------------|                   |
///     |                   |                   |                   |
/// ```
public class TestRefer extends Callflow {

	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest aliceRequest) throws ServletException, IOException {
		String status;

		status = aliceRequest.getRequestURI().getParameter("status");
		if (status == null) {
			status = "200";
		}

		Address referTo = sipFactory.createAddress(aliceRequest.getRequestURI().getParameter("refer"));
		referTo.getURI().setParameter("status", status);

		sendResponse(aliceRequest.createResponse(200), (aliceAck) -> {
			SipServletRequest aliceRefer = aliceAck.getSession().createRequest(REFER);
			aliceRefer.setAddressHeader("Refer-To", referTo);
			aliceRefer.setAddressHeader("Referred-By", aliceRequest.getTo());
			sendRequest(aliceRefer);

			// expect NOTIFY SIP/2.0 100 Trying
			expectRequest(aliceRefer.getSession(), NOTIFY, (notify) -> {

				sendResponse(notify.createResponse(200));

				// expect SIP/2.0 200 OK
				expectRequest(aliceRefer.getSession(), NOTIFY, (notify2) -> {
					sendResponse(notify2.createResponse(200));

					String sipFrag = new String((byte[]) notify2.getContent());
					if (sipFrag.contains("200")) {
						sendRequest(aliceRefer.getSession().createRequest(BYE), (byeResponse) -> {
							// do nothing;
						});
					}

				});
			});

		});

	}

}
