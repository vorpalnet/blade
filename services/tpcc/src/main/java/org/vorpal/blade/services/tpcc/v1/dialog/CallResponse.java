package org.vorpal.blade.services.tpcc.v1.dialog;

import io.swagger.v3.oas.annotations.media.Schema;

/// Returned by `POST /call`: the session it created and both dialog ids, so the caller can
/// list, connect or tear down either leg with the dialog operations.
public class CallResponse {

	@Schema(description = "the session the call created", example = "ABCD6789")
	public String sessionId;

	@Schema(description = "the dialog id of the party rung first", example = "BA5E")
	public String fromDialogId;

	@Schema(description = "the dialog id of the party called second; null if it was never called", example = "BA11")
	public String toDialogId;

	@Schema(description = "SIP status of the call: the final response that ended it, or 200", example = "200")
	public int status;

	@Schema(description = "SIP reason phrase", example = "OK")
	public String reasonPhrase;

	public CallResponse() {
		// do nothing;
	}

	public CallResponse(String sessionId, String fromDialogId, String toDialogId, int status, String reasonPhrase) {
		this.sessionId = sessionId;
		this.fromDialogId = fromDialogId;
		this.toDialogId = toDialogId;
		this.status = status;
		this.reasonPhrase = reasonPhrase;
	}

}
