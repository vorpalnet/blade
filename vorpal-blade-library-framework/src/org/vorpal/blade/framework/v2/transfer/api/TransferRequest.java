package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.transfer.TransferSettings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "style", "sessionKey", "dialogKey", "notification" })
public class TransferRequest implements Serializable {
	private static final long serialVersionUID = 1L;

	@Schema(description = "style of transfer: blind, attended or conference", defaultValue = "blind", nullable = true)
	public TransferSettings.TransferStyle style;

	@Schema(description = "X-Vorpal-Session header value or custom index key defined in the configuration file", defaultValue = "ABCD1234", nullable = true)
	public String sessionKey; // x-vorpal-session or other

//	@Schema(description = "Transferee dialog (SipSession) matching attribute", defaultValue = "ABCD1234", nullable = true)
	public DialogKey dialogKey;

	@Schema(description = "Type of notification: none, async, callback, jms", defaultValue = "async", nullable = true)
	public Notification notification;

//	public Transferee transferee;

	public Target target;

}
