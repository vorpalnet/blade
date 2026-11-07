package org.vorpal.blade.services.presence;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

/// The presence document (PIDF, RFC 3863) presence reads from PUBLISH and writes in NOTIFY.
///
/// Read: each `<tuple>`'s id and `<basic>`, open or closed, whatever namespace prefix the publisher
/// used. A tuple whose id is `skill-<name>` publishes a skill: the agent serves `<name>`, and is
/// Ready for it when that tuple is open. Any other tuple is the device itself (a softphone's own
/// tuple ids mean nothing here, so they cannot create skills). The publication is open when any
/// tuple is.
///
/// Written: one tuple whose `<basic>` is open only when the agent is Ready, then one `skill-<name>`
/// tuple per skill the agent serves, open when they are Ready for it, an RPID `on-the-phone`
/// activity (RFC 4480) while presence has them on a call, and a `<note>` with the status word.
final class Pidf {

	static final String CONTENT_TYPE = "application/pidf+xml";

	/// The id prefix that makes a tuple a skill.
	static final String SKILL = "skill-";

	private static final Pattern TUPLE = Pattern.compile(
			"<(?:\\w+:)?tuple\\b[^>]*?\\bid\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</(?:\\w+:)?tuple>", Pattern.DOTALL);
	private static final Pattern BASIC = Pattern.compile("<(?:\\w+:)?basic>\\s*(\\w+)\\s*</(?:\\w+:)?basic>");

	private Pidf() {
	}

	/// What one PUBLISH body says.
	static final class Published {
		boolean open;
		/// Skill to Ready for it.
		final Map<String, Boolean> skills = new LinkedHashMap<>();
	}

	/// The body read, or null when it is not PIDF or has no tuple with a `<basic>`.
	static Published read(SipServletRequest request) {
		try {
			return read(request.getContentType(), request.getRawContent());
		} catch (Exception e) {
			return null;
		}
	}

	static Published read(String type, byte[] body) {
		try {
			if (type == null || !type.toLowerCase().contains("pidf") || body == null) {
				return null;
			}
			Published out = new Published();
			boolean any = false;
			Matcher t = TUPLE.matcher(new String(body, StandardCharsets.UTF_8));
			while (t.find()) {
				Matcher b = BASIC.matcher(t.group(2));
				if (!b.find()) {
					continue;
				}
				any = true;
				boolean open = "open".equalsIgnoreCase(b.group(1));
				out.open |= open;
				String id = t.group(1).trim().toLowerCase();
				if (id.startsWith(SKILL) && id.length() > SKILL.length()) {
					out.skills.merge(id.substring(SKILL.length()), open, Boolean::logicalOr);
				}
			}
			return any ? out : null;
		} catch (Exception e) {
			return null;
		}
	}

	static byte[] document(Agent agent, long now) {
		String entity = xml("sip:" + agent.address);
		String status = agent.status(now);
		boolean onCall = agent.busyWith != null || agent.reservedFor != null;
		StringBuilder b = new StringBuilder();
		b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		b.append("<presence xmlns=\"urn:ietf:params:xml:ns:pidf\"");
		b.append(" xmlns:dm=\"urn:ietf:params:xml:ns:pidf:data-model\"");
		b.append(" xmlns:rpid=\"urn:ietf:params:xml:ns:pidf:rpid\"");
		b.append(" entity=\"").append(entity).append("\">\n");
		b.append(" <tuple id=\"agent\">\n");
		b.append("  <status><basic>").append(agent.ready(now) ? "open" : "closed").append("</basic></status>\n");
		b.append("  <contact>").append(entity).append("</contact>\n");
		b.append("  <note>").append(status).append("</note>\n");
		b.append(" </tuple>\n");
		for (String skill : agent.skills(now)) {
			b.append(" <tuple id=\"").append(xml(SKILL + skill)).append("\">");
			b.append("<status><basic>").append(agent.readyFor(skill, now) ? "open" : "closed").append("</basic></status>");
			b.append("<contact>").append(entity).append("</contact></tuple>\n");
		}
		if (onCall) {
			b.append(" <dm:person id=\"p\"><rpid:activities><rpid:on-the-phone/></rpid:activities></dm:person>\n");
		}
		b.append("</presence>\n");
		return b.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String xml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
