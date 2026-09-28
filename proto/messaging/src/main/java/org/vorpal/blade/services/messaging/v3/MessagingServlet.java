package org.vorpal.blade.services.messaging.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.v3.AsyncSipServlet;
import org.vorpal.blade.framework.v3.Callflow;

/// Chat rooms over SIP MESSAGE (RFC 3428).
///
/// A room is a SIP address, `sip:<room>@<domain>`; a MESSAGE to it is a post, and the room sends it
/// on to its other members as MESSAGEs of its own ([Delivery]). A meeting's chat is the room named
/// for the meeting.
///
/// ## One room, one session
///
/// [#sessionKey] keys every request by its room, so all posts to a room converge on one
/// application session wherever they arrive in the cluster ([Rooms]).
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class MessagingServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;

	public static MessagingSettingsManager settings;

	private final Membership membership = new Membership();

	@SipApplicationKey
	public static String sessionKey(SipServletRequest request) {
		String room = Rooms.nameOf(request);
		return (room == null) ? null : Rooms.key(room);
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settings = new MessagingSettingsManager(event);
		membership.start(event.getServletContext());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		membership.stop();
		settings.unregister();
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		// Anything but a post gets the framework's 501: the App Router sent it to the wrong place.
		return "MESSAGE".equals(request.getMethod()) ? new PostMessage() : null;
	}
}
