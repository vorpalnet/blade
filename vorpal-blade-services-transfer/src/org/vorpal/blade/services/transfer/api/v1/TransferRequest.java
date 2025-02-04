package org.vorpal.blade.services.transfer.api.v1;

import java.io.Serializable;

import org.vorpal.blade.services.transfer.TransferSettings;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonPropertyOrder({"style", "sessionKey", "dialogKey", "notification"})
public class TransferRequest implements Serializable {
	private static final long serialVersionUID = 1L;

	@Schema(description = "style of transfer: blind, attended or conference", defaultValue = "blind", nullable = true)
	public TransferSettings.TransferStyle style;

	@Schema(description = "X-Vorpal-Session header value or custom index key defined in the configuration file", defaultValue = "ABCD1234", nullable = true)
	public String sessionKey; // x-vorpal-session or other

//	@Schema(description = "Transferee dialog (SipSession) matching attribute", defaultValue = "ABCD1234", nullable = true)
	public DialogKey dialogKey;

	@JsonPropertyOrder({"style", "uid", "callbackName", "jmsQueueName"})
	public static class Notification implements Serializable {
		private static final long serialVersionUID = 1L;

		public enum Style {
			none, async, callback, jms
		}

		@Schema(description = "Style of notification; none: immediate response, async: request hangs until final response, callback: REST webhook, jms: Java Message Queue", defaultValue = "async", nullable = true)
		public Style style;

		@Schema(description = "Unique Identifier provided by the client to match notifications with requests", defaultValue = "1234567890", nullable = true)
		public String uid;

		@Schema(description = "Name of the webhook defined in the configuration file", defaultValue = "myWebhook", nullable = true)
		public String callbackName;

		@Schema(description = "Name of the JMS queue defined in the configuration file", defaultValue = "myJmsQueue", nullable = true)
		public String jmsQueueName;
	}

	public Notification notification;

//	public Transferee transferee;

	public Target target;

}
