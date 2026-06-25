// Gumball Agent — proto. MIT License, (c) 2026 Vorpal Networks, LLC.
package org.vorpal.blade.proto.gumball.agent;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.sdp.Sdp;
import org.vorpal.blade.proto.gumball.media.KurentoClient;
import org.vorpal.blade.proto.gumball.media.KurentoClient.AgentSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Demo 1 — AI agent gateway. Answers an inbound INVITE as a UAS, anchors the caller's media on a
 * Kurento RtpEndpoint, wires the audio tap to the int8 inference bridge, and branches on the
 * conversation outcome. The conversation itself runs on the media/inference plane; this callflow
 * is the supervisor — the readable top-to-bottom lifecycle.
 */
public class AgentCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	// Only these (Strings) survive failover serialization. Live Kurento refs are never stored;
	// a recovered node calls KurentoClient.reattach(kurentoWsUrl, kurentoSessionId) and re-uses
	// pipelineId/rtpEndpointId to reclaim the still-running pipeline. See media/README.md.
	private String kurentoWsUrl;
	private String kurentoSessionId;
	private String pipelineId;
	private String rtpEndpointId;

	@Override
	public void process(SipServletRequest invite) throws ServletException, IOException {
		try {
			byte[] raw = invite.getRawContent();
			if (raw == null) {
				sendResponse(invite.createResponse(488)); // no offer SDP -> Not Acceptable Here
				return;
			}
			String callerOffer = new String(raw, "UTF-8");

			sendResponse(invite.createResponse(180)); // Ringing

			// Anchor media on Kurento; KMS builds the SDP answer from the caller's offer.
			AgentSettings settings = currentSettings();
			KurentoClient kurento = KurentoClient.connect(settings.getKurentoUrl());
			KurentoClient.Pipeline pipeline = kurento.createPipeline();
			KurentoClient.RtpEndpoint rtp = pipeline.createRtpEndpoint();
			String answerSdp = rtp.processOffer(callerOffer);

			// Persist ids for failover re-attach (only Strings — serialization-safe).
			this.kurentoWsUrl = kurento.url();
			this.kurentoSessionId = kurento.sessionId();
			this.pipelineId = pipeline.id();
			this.rtpEndpointId = rtp.id();

			// Round-trip through the SDP model (here we'd pin PCMU/PCMA for telco).
			Sdp answer = Sdp.parse(answerSdp);

			// Wire the media. "loopback" echoes the caller through a stock Kurento (rig test, no
			// custom module); "agent" taps audio <-> int8 inference via the GumballAgentBridge.
			boolean loopback = "loopback".equalsIgnoreCase(settings.getBridgeMode());
			AgentSession agent = loopback ? null : pipeline.connectAgent(rtp, agentConfig(settings));
			if (loopback) {
				rtp.connect(rtp.id()); // echo: caller hears their own audio back
			}

			// Answer the caller; media now flows Caller <-> Kurento.
			SipServletResponse ok = invite.createResponse(200);
			ok.setContent(answer.toString().getBytes("UTF-8"), "application/sdp");
			sendResponse(ok, ack -> {
				// call is up; media runs on the Kurento plane
			});

			if (loopback) {
				// no AI outcome in loopback; the call echoes until the caller sends BYE
				// (handled by the framework). The pipeline is released on session teardown.
				return;
			}

			// Branch on the AI's outcome (custom bridge emits "AgentOutcome" events).
			agent.onOutcome(event -> {
				String kind = event.path("data").path("outcome").asText("HANGUP");
				switch (kind) {
				case "BOOKED":
					writeCrmDisposition(invite, event);
					hangup(invite, pipeline);
					break;
				case "TRANSFER":
					escalateToHuman(invite, event, pipeline);
					break;
				case "TIMEOUT":
				case "HANGUP":
				default:
					hangup(invite, pipeline);
				}
			});

		} catch (Exception ex) {
			sipLogger.severe(ex);
			try {
				sendResponse(invite.createResponse(500));
			} catch (Exception ignore) {
				// best effort
			}
		}
	}

	private AgentSettings currentSettings() {
		SettingsManager<AgentSettings> m = AgentSipServlet.settingsManager;
		AgentSettings s = (m != null) ? m.getCurrent() : null;
		return (s != null) ? s : new AgentSettingsSample();
	}

	private ObjectNode agentConfig(AgentSettings settings) {
		ObjectNode cfg = MAPPER.createObjectNode();
		cfg.put("systemPrompt", settings.getSystemPrompt());
		cfg.put("language", settings.getLanguage());
		return cfg;
	}

	/** AI chose to end the call: send BYE on the live dialog and release the media pipeline. */
	private void hangup(SipServletRequest invite, KurentoClient.Pipeline pipeline) {
		try {
			SipSession ss = invite.getSession();
			if (ss != null && ss.isValid() && ss.getState() == SipSession.State.CONFIRMED) {
				sendRequest(ss.createRequest("BYE"));
			}
		} catch (Exception e) {
			sipLogger.severe(e);
		} finally {
			try {
				pipeline.release();
			} catch (Exception e) {
				sipLogger.severe(e);
			}
		}
	}

	/**
	 * Escalate to a human. TODO: bridge caller &lt;-&gt; settings.getTransferTarget() as a B2BUA
	 * leg (InitialInvite / BlindTransfer), keeping Kurento in the path for future agent-assist.
	 * Until that's built, log and release so the call does not dangle.
	 */
	private void escalateToHuman(SipServletRequest invite, JsonNode event, KurentoClient.Pipeline pipeline) {
		sipLogger.warning("Gumball: TRANSFER requested; human-bridge not yet implemented, releasing call.");
		hangup(invite, pipeline);
	}

	/** TODO: POST the outcome/summary to the customer CRM. For now, log it. */
	private void writeCrmDisposition(SipServletRequest invite, JsonNode event) {
		sipLogger.info("Gumball: BOOKED; disposition=" + event.toString());
	}

}
