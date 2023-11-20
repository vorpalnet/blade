/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package org.vorpal.blade.services.queue;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.b2bua.InitialInvite;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.Translation;

/**
 * @author Jeff McDonald
 *
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class QueueServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;

	/**
	 * Contains the latest configuration and queues.
	 */
	public static QueueSettingsManager settingsManager;

	/*
	 * This is invoked when the servlet starts up.
	 */

	@Override
	public void servletCreated(SipServletContextEvent event) {

		try {

			// Overloaded initialize on QueueSettingsManager method will construct the
			// queues
			settingsManager = new QueueSettingsManager(event, new QueueConfigSample());
			sipLogger.fine("servletCreated...");

		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.severe(e);
			} else {
				e.printStackTrace();
			}
		}

	}

	/*
	 * This is the outbound INVITE request to Bob, it can be modified.
	 */
	@Override
	public void callStarted(SipServletRequest request) throws ServletException, IOException {

		if (request.getMethod().equals("INVITE")) {

			// Find the callflow. This is a bit of an API kludge, sorry!
			InitialInvite callflow = (InitialInvite) request.getAttribute("callflow");
//		sipLogger.severe(request, "QueueServlet.callStarted... callflow: " + callflow);

			SipServletRequest inboundRequest = callflow.getInboundRequest();

//		sipLogger.fine(request, "Inbound Request: \n" + inboundRequest.toString());

			// Find a translation for the request (don't forget about the default)
			Translation t = settingsManager.getCurrent().findTranslation(inboundRequest);

			String queueName = (String) t.getAttribute("queue");
			if (queueName != null) {
				sipLogger.fine(request, "Found matching translation! ");

				// Send 180 Ringing, do not send outbound INVITE (yet)
				this.doNotProcess(request, 180);

				// Place the callflow in the queue to be processed later

				CallflowQueue queue = settingsManager.getQueue(queueName);
				ConcurrentLinkedDeque<Callflow> deque = queue.getCallflows();
				deque.addFirst(callflow);

//			sipLogger.severe(request, "QueueServlet.callStarted... queueName: " + queueName);
//			CallflowQueue callflowQueue = settingsManager.getQueue(queueName);
//			sipLogger.severe(request, "QueueServlet.callStarted... callflowQueue: " + callflowQueue);
//			if (callflowQueue != null) {
//				ConcurrentLinkedDeque<Callflow> deque = callflowQueue.getCallflows();
//				sipLogger.severe(request, "QueueServlet.callStarted... deque: " + deque);
//				if (deque != null) {
//					deque.addFirst(callflow);
//				}
//			}

			} else {
				sipLogger.warning(request, "No matching translation found. :-(");
			}
		} else {

		}

	}

	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest request) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse response) throws ServletException, IOException {
	}

	@Override
	public void callAbandoned(SipServletRequest request) throws ServletException, IOException {
	}

	@Override
	public void servletDestroyed(SipServletContextEvent event) {
		try {
			sipLogger.info("servletDestroyed...");
			settingsManager.unregister();
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.severe(e);
			} else {
				e.printStackTrace();
			}
		}
	}

}
