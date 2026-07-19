package org.vorpal.blade.services.tpcc.v1.dialog;

import io.swagger.v3.oas.annotations.media.Schema;

/// Returned by `POST /dialog/{sessionId}` (create leg): echoes the sessionId and,
/// crucially, the generated `dialogId` so the caller can chain connect/delete.
public class DialogResponse {

	@Schema(description = "the session this leg belongs to", example = "ABCD6789")
	public String sessionId;

	@Schema(description = "the new leg's dialog id (X-Vorpal-Dialog)", example = "BA5E")
	public String dialogId;

	@Schema(description = "SIP status of the leg's answer", example = "200")
	public int status;

	@Schema(description = "SIP reason phrase", example = "OK")
	public String reasonPhrase;

	public DialogResponse() {
		// do nothing;
	}

	public DialogResponse(String sessionId, String dialogId, int status, String reasonPhrase) {
		this.sessionId = sessionId;
		this.dialogId = dialogId;
		this.status = status;
		this.reasonPhrase = reasonPhrase;
	}

}
