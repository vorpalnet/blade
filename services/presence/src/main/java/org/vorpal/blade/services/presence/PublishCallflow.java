package org.vorpal.blade.services.presence;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

public class PublishCallflow extends Callflow {

	/// The PIDF `<basic>` status (RFC 3863): open or closed, whatever namespace
	/// prefix the publisher used.
	private static final Pattern BASIC = Pattern.compile("<(?:\\w+:)?basic>\\s*(\\w+)\\s*</(?:\\w+:)?basic>");

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		SipServletResponse response = request.createResponse(200);
		response.setExpires(request.getExpires());
		sendResponse(response);

		String entity = PresenceServlet.getAccountName(request.getTo());
		int expires = request.getExpires();
		String basic = basic(request);
		Events.publish(BladeEventTypes.PRESENCE_PUBLISHED, entity, data -> data
				.put("entity", entity)
				.put("event", request.getHeader("Event"))
				.put("expires", (expires < 0) ? null : Integer.valueOf(expires))
				.put("basic", basic)
				.put("node", SettingsManager.getServerName()));
	}

	private static String basic(SipServletRequest request) {
		try {
			String type = request.getContentType();
			if (type == null || !type.toLowerCase().contains("pidf")) {
				return null;
			}
			byte[] body = request.getRawContent();
			Matcher m = BASIC.matcher(new String(body, java.nio.charset.StandardCharsets.UTF_8));
			return m.find() ? m.group(1) : null;
		} catch (Exception e) {
			return null;
		}
	}

}
