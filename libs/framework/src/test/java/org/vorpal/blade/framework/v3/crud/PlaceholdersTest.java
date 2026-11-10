package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;

/// `${name}` in a regex pattern or a JsonPath lands as data: quoted in a
/// regex, escaped in a JsonPath string, and an operation with an unresolved
/// one is skipped.
class PlaceholdersTest {

	private static final String SDP = "v=0\r\n" //
			+ "o=- 1 1 IN IP4 192.0.2.1\r\n" //
			+ "s=-\r\n" //
			+ "c=IN IP4 192.0.2.1\r\n" //
			+ "t=0 0\r\n" //
			+ "m=audio 10000 RTP/AVP 0\r\n" //
			+ "a=sendonly\r\n" //
			+ "a=label:101\r\n" //
			+ "m=audio 10002 RTP/AVP 0\r\n" //
			+ "a=sendonly\r\n" //
			+ "a=label:102\r\n" //
			+ "m=audio 10004 RTP/AVP 0\r\n" //
			+ "a=label:103\r\n" //
			+ "a=inactive\r\n";

	private static final String NOT_KEPT = "$.media[?(@.attributes[?(@.name == 'label')].value noneof ['${keep}'])]";

	@BeforeEach
	void wire() {
		SettingsManager.setSipFactory(new DetachedSipFactory());
		SettingsManager.setSipLogger(new CapturingLogger());
	}

	private static DetachedRequest invite(String body, String contentType) throws Exception {
		DetachedRequest invite = new DetachedRequest(new DetachedApplicationSession("app"), "INVITE",
				"<sip:alice@example.com>;tag=a1", "<sip:bob@example.com>");
		invite.setContent(body, contentType);
		return invite;
	}

	private static String var(DetachedRequest msg, String name) {
		return MessageHelper.getVariable(msg.getApplicationSession(), name);
	}

	@Test
	void aRegexValueMatchesItselfLiterally() throws Exception {
		String xml = "<stream id=\"tpwx+AA8/Sl==\"><label>2001</label></stream>"
				+ "<stream id=\"tpwxAAAA8/Sl==\"><label>2002</label></stream>";
		DetachedRequest msg = invite(xml, "application/rs-metadata+xml");
		MessageHelper.putVariable(msg.getApplicationSession(), "streamId", "tpwx+AA8/Sl==");
		new ReadOperation("body", "<stream id=\"${streamId}\"><label>(?<label>[^<]+)</label>").process(msg);
		assertEquals("2001", var(msg, "label"), "the + is a character, not a quantifier");
	}

	@Test
	void aRegexWithAnUnsetPlaceholderIsSkipped() throws Exception {
		DetachedRequest msg = invite("<label>2001</label>", "application/rs-metadata+xml");
		new ReadOperation("body", "<stream id=\"${streamId}\"><label>(?<label>[^<]+)</label>").process(msg);
		assertNull(var(msg, "label"));
		new UpdateOperation("body", "${streamId}", "changed").process(msg);
		assertEquals("<label>2001</label>", msg.getContent());
	}

	@Test
	void aPlainRegexStillCompilesWhenTheConfigLoads() {
		org.junit.jupiter.api.Assertions.assertThrows(java.util.regex.PatternSyntaxException.class,
				() -> new ReadOperation("body", "(?<broken"));
	}

	@Test
	void aJsonPathFilterTakesTheValue() throws Exception {
		DetachedRequest msg = invite(SDP, "application/sdp");
		MessageHelper.putVariable(msg.getApplicationSession(), "keep", "102");
		new SdpDeleteOperation(NOT_KEPT).process(msg);
		String out = (String) msg.getContent();
		assertTrue(out.contains("a=label:102"));
		assertFalse(out.contains("a=label:101"));
		assertFalse(out.contains("a=label:103"));
	}

	@Test
	void aJsonPathValueCannotCloseItsQuotes() throws Exception {
		DetachedRequest msg = invite(SDP, "application/sdp");
		MessageHelper.putVariable(msg.getApplicationSession(), "keep", "x'] || true || ['");
		SdpReadOperation read = new SdpReadOperation();
		read.getExpressions().put("labels",
				"$.media[?(@.attributes[?(@.name == 'label')].value anyof ['${keep}'])].port");
		read.process(msg);
		assertEquals("[]", var(msg, "labels"));
	}

	@Test
	void aJsonPathWithAnUnsetPlaceholderIsSkipped() throws Exception {
		DetachedRequest msg = invite(SDP, "application/sdp");
		new SdpDeleteOperation(NOT_KEPT).process(msg);
		assertEquals(SDP, msg.getContent(), "noneof ['${keep}'] would have matched every media section");
	}
}
