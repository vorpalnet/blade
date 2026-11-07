package org.vorpal.blade.framework.v2.transfer.api;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Configuration for transfer operation notification delivery.
 *
 * <p>Specifies how the caller should be notified of transfer results:
 * a waited response, an immediate one, or a webhook callback.
 */
@JsonPropertyOrder({"style", "uid", "callbackName", "jmsQueueName"})
public class Notification implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum Style {
		none, async, callback, jms
	}

	@Schema(description = "Style of notification; async (the default): the request waits and answers with the outcome; none: immediate 202; callback: immediate 202, then every event POSTed to the webhook named by callbackName; jms: not implemented, answers like none (the event bus carries every transfer event regardless)", defaultValue = "async", nullable = true)
	public Style style;

	@Schema(description = "Unique Identifier provided by the client to match notifications with requests", defaultValue = "1234567890", nullable = true)
	public String uid;

	@Schema(description = "Name of the webhook defined in the configuration file", defaultValue = "myWebhook", nullable = true)
	public String callbackName;

	@Schema(description = "Name of the JMS queue defined in the configuration file", defaultValue = "myJmsQueue", nullable = true)
	public String jmsQueueName;
}
