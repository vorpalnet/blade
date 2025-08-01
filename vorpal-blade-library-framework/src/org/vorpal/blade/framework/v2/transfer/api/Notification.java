package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonPropertyOrder({"style", "uid", "callbackName", "jmsQueueName"})
public class Notification implements Serializable {
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
