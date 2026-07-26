package org.vorpal.blade.services.events;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;

import com.fasterxml.jackson.databind.JsonNode;

/// A reference consumer for the event bus — the "calendar app" that subscribes
/// to scheduled-meeting events.
///
/// Its only job here is to prove the pub/sub story end to end: it takes the
/// CloudEvent the attendant emitted for `net.vorpal.attendant.meeting.scheduled`
/// off the topic and logs the appointment it would create. A real calendar
/// integration would replace the log line with an iCalendar write or a partner
/// calendar-API call — but it is a *separate* app, decoupled from the producer,
/// which is the whole point of the bus.
///
/// **Why these activation properties.** `destinationType = Topic` with a
/// **durable** subscription means an appointment isn't lost if this app is down
/// when the caller schedules it. `topicMessagesDistributionMode =
/// One-Copy-Per-Application` makes the whole cluster act as one logical
/// subscriber, so a scheduled meeting is handled once cluster-wide, not once per
/// engine node. The `messageSelector` filters on the JMS `eventType` property
/// [EventPublisher] stamps, so this MDB never deserializes an event it doesn't
/// care about. The bus fans out, so adding a second consumer (an audit logger,
/// a notifier) is just another MDB with its own subscription name — no producer
/// change.
@TransactionManagement(TransactionManagementType.BEAN)
@MessageDriven(mappedName = EventBus.TOPIC_JNDI, activationConfig = {
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
		@ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
		@ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = "BladeEventBusCalendarSub"),
		@ActivationConfigProperty(propertyName = "clientId", propertyValue = "BladeEventBusCalendar"),
		@ActivationConfigProperty(propertyName = "topicMessagesDistributionMode", propertyValue = "One-Copy-Per-Application"),
		@ActivationConfigProperty(propertyName = "distributedDestinationConnection", propertyValue = "EveryMember"),
		@ActivationConfigProperty(propertyName = "messageSelector", propertyValue = "eventType = 'net.vorpal.attendant.meeting.scheduled'"),
		@ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge") })
public class CalendarEventListener implements MessageListener {

	private static final Logger logger = Logger.getLogger(CalendarEventListener.class.getName());

	@Override
	public void onMessage(Message message) {
		if (!(message instanceof TextMessage)) {
			logger.warning("CalendarEventListener: ignoring non-TextMessage " + message.getClass().getName());
			return;
		}

		try {
			String json = ((TextMessage) message).getText();
			CloudEvent event = CloudEvent.fromJson(json);

			String who = text(event, "who");
			String when = text(event, "when_text");
			if (when == null) {
				when = text(event, "when");
			}

			logger.info("CalendarEventListener: would create appointment"
					+ (who != null ? " with " + who : "")
					+ (when != null ? " at " + when : "")
					+ " [event id=" + event.getId() + ", subject=" + event.getSubject() + "]");
		} catch (Exception e) {
			// Consume the message rather than force redelivery: a malformed
			// payload won't fix itself on retry.
			logger.log(Level.SEVERE, "CalendarEventListener: failed to handle event", e);
		}
	}

	/// Pull a string field out of the CloudEvent's `data` payload, or null.
	private static String text(CloudEvent event, String field) {
		JsonNode data = event.getData();
		if (data == null) {
			return null;
		}
		JsonNode node = data.get(field);
		return (node == null || node.isNull()) ? null : node.asText();
	}
}
