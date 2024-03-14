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
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.Translation;
import org.vorpal.blade.services.queue.config.QueueAttributes;
import org.vorpal.blade.services.queue.config.QueueConfig;
import org.vorpal.blade.services.queue.config.QueueConfigSample;
import org.vorpal.blade.services.queue.config.QueueSettingsManager;

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

	public static Map<String, Queue> queues = new HashMap<>();
	public static QueueSettingsManager settingsManager;

	@Override
	public void servletCreated(SipServletContextEvent event) {

		try {
			settingsManager = new QueueSettingsManager(event, new QueueConfigSample());
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.severe(e);
			} else {
				e.printStackTrace();
			}
		}

	}

	@Override
	public void servletDestroyed(SipServletContextEvent event) {
		try {
			sipLogger.info("servletDestroyed...");

			for (String key : this.queues.keySet()) {
				this.queues.get(key).stopTimers();
			}

			settingsManager.unregister();
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.severe(e);
			} else {
				e.printStackTrace();
			}
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		QueueConfig config = settingsManager.getCurrent();

		if (request.getMethod().equals("INVITE") && request.isInitial()) {
			Translation t = config.findTranslation(request);

			if (t != null) {
				String queueName = (String) t.getAttribute("queue");

				if (queueName != null) {
					sipLogger.fine(request, "Found matching translation... queue=" + queueName);
					Queue queue = queues.get(queueName);

					if (queue != null) {
						QueueAttributes queueAttributes = queue.attributes;
						QueueCallflow queueCallflow = new QueueCallflow(queueName, queueAttributes);
						queue.callflows.add(queueCallflow);
						queue.statistics.intervalTask();
						callflow = queueCallflow;
					} else {
						sipLogger.severe(request, "No queue defined for: " + queueName);
					}

				}
			}

		}

		if (null == callflow) {
			callflow = super.chooseCallflow(request);
		}

		return callflow;
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

}
