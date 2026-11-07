package org.vorpal.blade.services.tpcc.v1.dialog;

import io.swagger.v3.oas.annotations.media.Schema;

/// The body of `POST /call`: the two parties of a one-shot call. `from` rings first; `to` is
/// called once `from` answers, so whoever clicked the link hears the call from its first word.
public class CallRequest {

	@Schema(description = "the party rung first, usually the person who asked for the call")
	public Dialog from;

	@Schema(description = "the party called once `from` answers")
	public Dialog to;

	public CallRequest() {
		// do nothing;
	}

}
