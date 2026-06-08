package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One step in a [ResponseScript] `send` sequence.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "status", "reasonPhrase", "delay", "sdp" })
public class ResponseStep implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final String SDP_HOLD = "hold";
	public static final String SDP_NONE = "none";

	private int status = 200;
	private String reasonPhrase;
	private String delay;
	private String sdp;

	public ResponseStep() {
	}

	public ResponseStep(int status) {
		this.status = status;
	}

	public ResponseStep(int status, String delay) {
		this.status = status;
		this.delay = delay;
	}

	@JsonPropertyDescription("SIP status code to send (100-699).")
	public int getStatus() {
		return status;
	}

	public void setStatus(int status) {
		this.status = status;
	}

	@JsonPropertyDescription("Optional custom reason phrase (e.g. 'Busy Here').")
	public String getReasonPhrase() {
		return reasonPhrase;
	}

	public void setReasonPhrase(String reasonPhrase) {
		this.reasonPhrase = reasonPhrase;
	}

	@JsonPropertyDescription("Wait this long before sending (e.g. 200ms, 2s). Default: immediately.")
	public String getDelay() {
		return delay;
	}

	public void setDelay(String delay) {
		this.delay = delay;
	}

	@JsonPropertyDescription("SDP on this step: 'hold' (blackhole/mute answer derived from the offer — default for 2xx) or 'none' (bare response).")
	public String getSdp() {
		return sdp;
	}

	public void setSdp(String sdp) {
		this.sdp = sdp;
	}
}
