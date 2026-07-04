package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Scripts a scenario's response behavior.
///
/// **answer role** — `send` is played in order: each step waits its `delay`,
/// then sends its status. Steps after the first final (≥200) status are
/// ignored with a warning. A 2xx answer carries an RFC 3264 inactive hold SDP derived
/// from the offer unless the step says otherwise. After a 2xx, `refer` (if
/// set) runs a REFER transfer, and `autoByeAfter` (if set) tears the call
/// down.
///
/// **originate role** — `expectFinal` is a status filter (same syntax as
/// rule `statusRange`: `200`, `2xx`, `200-299`, `!5xx`) the call's final
/// response must match; mismatches count as expectation failures in the
/// metrics. `autoByeAfter` overrides the configured call duration.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "send", "expectFinal", "refer", "referStatus", "autoByeAfter" })
public class ResponseScript implements Serializable {
	private static final long serialVersionUID = 1L;

	private List<ResponseStep> send = new LinkedList<>();
	private String expectFinal;
	private String refer;
	private String referStatus;
	private String autoByeAfter;

	public ResponseScript() {
	}

	/// Parses a human-readable delay to milliseconds: a bare integer is
	/// milliseconds; the suffixes `ms`, `s`, `m`, `h` are honored.
	/// Returns `0` if null or unparseable.
	public static long parseMillis(String value) {
		if (value == null) {
			return 0;
		}
		value = value.trim().toLowerCase();
		try {
			if (value.endsWith("ms")) {
				return Long.parseLong(value.substring(0, value.length() - 2).trim());
			} else if (value.endsWith("s")) {
				return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 1000L;
			} else if (value.endsWith("m")) {
				return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 60_000L;
			} else if (value.endsWith("h")) {
				return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 3_600_000L;
			}
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@JsonPropertyDescription("Ordered response steps for the answer role. Empty means a single 200 with a hold SDP.")
	public List<ResponseStep> getSend() {
		return send;
	}

	public void setSend(List<ResponseStep> send) {
		this.send = send;
	}

	@JsonPropertyDescription("Originate role: status filter the final response must match (200, 2xx, 200-299, !5xx). Default 2xx. Mismatches count as expectation failures.")
	public String getExpectFinal() {
		return expectFinal;
	}

	public void setExpectFinal(String expectFinal) {
		this.expectFinal = expectFinal;
	}

	@JsonPropertyDescription("Answer role: after a 2xx answer, send a REFER transfer to this address.")
	public String getRefer() {
		return refer;
	}

	public void setRefer(String refer) {
		this.refer = refer;
	}

	@JsonPropertyDescription("Optional status URI-parameter stamped on the Refer-To target — tells a downstream test app what to answer the transferred INVITE with.")
	public String getReferStatus() {
		return referStatus;
	}

	public void setReferStatus(String referStatus) {
		this.referStatus = referStatus;
	}

	@JsonPropertyDescription("Tear the call down this long after answer (e.g. 500ms, 30s, 5m). Answer role: after the 2xx. Originate role: overrides the configured call duration.")
	public String getAutoByeAfter() {
		return autoByeAfter;
	}

	public void setAutoByeAfter(String autoByeAfter) {
		this.autoByeAfter = autoByeAfter;
	}
}
