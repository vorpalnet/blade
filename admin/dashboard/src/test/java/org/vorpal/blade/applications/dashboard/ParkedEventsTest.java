package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/// A parked event's headers, read from the broker's XML rendering of it.
class ParkedEventsTest {

	static final String PARKED = "<mes:WLJMSMessage xmlns:mes=\"http://www.bea.com/WLS/JMS/Message\"><mes:Header>"
			+ "<mes:JMSMessageID>ID:&lt;607188.1792046006952.0></mes:JMSMessageID>"
			+ "<mes:JMSDeliveryMode>PERSISTENT</mes:JMSDeliveryMode><mes:JMSTimestamp>1792046006952</mes:JMSTimestamp>"
			+ "<mes:Properties><mes:property name=\"JMS_BEA_DeliveryFailureReason\"><mes:Int>2</mes:Int></mes:property>"
			+ "<mes:property name=\"eventId\"><mes:String>9f4ca73e-e8c8-4e78-ad9b-e513f210a076</mes:String></mes:property>"
			+ "<mes:property name=\"eventType\"><mes:String>org.vorpal.blade.trunk.registered</mes:String></mes:property>"
			+ "<mes:property name=\"eventSubject\"><mes:String>trunk_a</mes:String></mes:property>"
			+ "</mes:Properties></mes:Header></mes:WLJMSMessage>";

	@Test
	void aParkedEventReadsAsItsHeaders() {
		ObjectNode row = ParkedEvents.message(PARKED);
		assertEquals("ID:<607188.1792046006952.0>", row.path("id").asText(), "the id a delete names");
		assertEquals(1792046006952L, row.path("at").asLong());
		assertEquals("org.vorpal.blade.trunk.registered", row.path("type").asText());
		assertEquals("trunk_a", row.path("subject").asText());
		assertEquals("a subscriber failed it until the redelivery limit", row.path("reason").asText());
	}

	@Test
	void anUnknownReasonShowsItsCode() {
		assertEquals("broker reason 1", ParkedEvents.reason("1"));
		assertNull(ParkedEvents.reason(null));
	}

	@Test
	void anUnreadableMessageIsSkipped() {
		assertNull(ParkedEvents.message("<not xml"));
	}
}
