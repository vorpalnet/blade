package org.vorpal.blade.services.player;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.media.MediaCallflow;

/// In-dialog INFO for the player: feed any DTMF (a SIP INFO `application/dtmf-relay` / `application/dtmf`
/// body) into the call's active [MediaCallflow#prompt] collect, then 200 OK. Non-DTMF INFO (e.g. a
/// video keyframe request) delivers no digit and is simply acknowledged. The app stays pure-309 — the
/// framework bridge routes the digit to the driver's SignalDetector.
public class PlayerInfo extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest info) throws ServletException, IOException {
		MediaCallflow.deliverInfoDtmf(info);
		sendResponse(info.createResponse(200));
	}
}
