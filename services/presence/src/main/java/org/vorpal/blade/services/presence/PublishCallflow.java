package org.vorpal.blade.services.presence;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

/// An agent's device publishes Ready or Not Ready, and the skills they serve, one `skill-<name>`
/// tuple each ([Pidf]) (RFC 3903, event package `presence`).
///
/// - A first publication carries a PIDF body and is given an entity tag (`SIP-ETag`).
/// - A refresh names that tag in `SIP-If-Match`; with a body it also changes the state. An unknown
///   tag is `412`, and the device starts again with a first publication.
/// - `Expires: 0` with the tag withdraws that device's publication.
///
/// Only the agent may publish for themselves: the From address must be the To address.
///
/// Publishing `open` also clears a Not Ready that presence set after an unanswered call. An agent
/// who turns Ready starts their idle time now. Saving the agent re-indexes them under the skills
/// they now serve and are Ready for ([Agents#save]), and every skill they serve then looks for a
/// waiting call.
public class PublishCallflow extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		long t0 = System.nanoTime();
		String event = request.getHeader("Event");
		if (event == null || !event.trim().toLowerCase().startsWith("presence")) {
			SipServletResponse bad = request.createResponse(489);
			bad.setHeader("Allow-Events", "presence");
			sendResponse(bad);
			return;
		}
		String address = PresenceServlet.getAccountName(request.getTo());
		if (!address.equals(PresenceServlet.getAccountName(request.getFrom()))) {
			sendResponse(request.createResponse(403, "Publish Your Own Presence"));
			return;
		}
		PresenceSettings settings = PresenceServlet.settingsManager.getCurrent();
		int expires = request.getExpires();
		if (expires < 0 || expires > settings.getMaxExpiresSeconds()) {
			expires = settings.getMaxExpiresSeconds();
		}
		String ifMatch = request.getHeader("SIP-If-Match");
		boolean hasBody = request.getContentLength() > 0;
		Pidf.Published body = hasBody ? Pidf.read(request) : null;
		if (hasBody && body == null) {
			sendResponse(request.createResponse(415, "PIDF Expected"));
			return;
		}

		SipApplicationSession app = request.getApplicationSession();
		long now = System.currentTimeMillis();
		Agent agent = Agents.of(app, address);
		agent.prune(now);
		boolean wasReady = agent.ready(now);
		String before = agent.status(now);
		Set<String> skillsBefore = agent.skills(now);
		String readyBefore = readyFor(agent, skillsBefore, now);

		Agent.Publication publication;
		if (ifMatch != null) {
			publication = agent.publications.remove(ifMatch.trim());
			if (publication == null) {
				sendResponse(request.createResponse(412));
				return;
			}
		} else if (!hasBody) {
			sendResponse(request.createResponse(400, "Initial PUBLISH Needs A Body"));
			return;
		} else {
			publication = new Agent.Publication();
		}

		SipServletResponse ok = request.createResponse(200);
		if (expires == 0) {
			ok.setExpires(0);
		} else {
			if (body != null) {
				publication.open = body.open;
				publication.skills = body.skills;
				if (publication.open) {
					agent.held = false;
				}
			}
			publication.expiresAt = now + expires * 1000L;
			String etag = UUID.randomUUID().toString();
			agent.publications.put(etag, publication);
			ok.setHeader("SIP-ETag", etag);
			ok.setExpires(expires);
		}
		boolean ready = agent.ready(now);
		if (ready && !wasReady) {
			agent.idleSince = now;
		}
		long tRead = System.nanoTime();
		Agents.save(app, agent, now);
		long tSave = System.nanoTime();
		sendResponse(ok);
		long tSent = System.nanoTime();

		String after = agent.status(now);
		TreeSet<String> skillsAfter = agent.skills(now);
		String readyAfter = readyFor(agent, skillsAfter, now);
		if (!after.equals(before) || !readyAfter.equals(readyBefore)) {
			sipLogger.info(request, "presence: " + address + " is " + after + ", ready for [" + readyAfter + "]");
			Agents.notifyWatchers(app, agent, now);
		}
		if (ready && !readyAfter.equals(readyBefore)) {
			Skills.wake(address);
		}
		long tNotify = System.nanoTime();

		int granted = expires;
		Events.publish(BladeEventTypes.PRESENCE_PUBLISHED, address, data -> data
				.put("entity", address)
				.put("event", event)
				.put("expires", Integer.valueOf(granted))
				.put("basic", (body == null) ? null : body.open ? "open" : "closed")
				.put("skills", String.join(",", skillsAfter))
				.put("status", after)
				.put("node", SettingsManager.getServerName()));
		long tEvent = System.nanoTime();
		sipLogger.info(request, "presence: diag publish " + address + " ms read=" + ms(tRead - t0) + " save="
				+ ms(tSave - tRead) + " send=" + ms(tSent - tSave) + " notify=" + ms(tNotify - tSent) + " event="
				+ ms(tEvent - tNotify) + " total=" + ms(tEvent - t0));
	}

	private static String ms(long nanos) {
		return String.format("%.1f", nanos / 1e6);
	}

	/// The skills `agent` is Ready for, as one comparable string.
	private static String readyFor(Agent agent, Set<String> skills, long now) {
		StringBuilder b = new StringBuilder();
		for (String skill : skills) {
			if (agent.readyFor(skill, now)) {
				b.append(b.length() > 0 ? ", " : "").append(skill);
			}
		}
		return b.toString();
	}
}
