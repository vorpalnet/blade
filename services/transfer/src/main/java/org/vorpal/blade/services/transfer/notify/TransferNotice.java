package org.vorpal.blade.services.transfer.notify;

import java.io.Serializable;
import java.util.List;

import org.vorpal.blade.framework.v2.transfer.api.TransferRequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One transfer event, as a webhook receives it in a POST, a WebSocket client
/// in a text frame, and the event bus as the payload of this application's
/// own `org.vorpal.blade.services.transfer.<event>` type. The events are
/// [TransferNotices#EVENTS]: the framework's five transfer events, at the same
/// moments, plus `consultationStarted` for an attended transfer.
///
/// `method`, `status` and `description` follow the REST API's own response;
/// `request` is the REST request that started the transfer, absent for a
/// REFER's.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "event", "time", "vorpalId", "sessionKeys", "uid", "style", "referTo", "method", "status",
		"description", "request" })
public class TransferNotice implements Serializable {
	private static final long serialVersionUID = 1L;

	public String event;
	/// When it happened, ISO-8601 in UTC.
	public String time;
	/// The call's Vorpal-ID.
	public String vorpalId;
	/// The call's session keys, as the session selectors compute them from
	/// its first INVITE: what a REST request names as `sessionKey`.
	public List<String> sessionKeys;
	/// The REST request's `notification.uid`, echoed so a client can match.
	public String uid;
	public String style;
	/// The transfer target.
	public String referTo;
	public String method;
	public Integer status;
	public String description;
	public TransferRequest request;
}
