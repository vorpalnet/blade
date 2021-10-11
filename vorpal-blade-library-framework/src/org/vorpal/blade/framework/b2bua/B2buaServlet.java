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

package org.vorpal.blade.framework.b2bua;

import java.io.IOException;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.TimerListener;
import javax.servlet.sip.TimerService;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;
import org.vorpal.blade.framework.logging.Logger.Direction;

/**
 * @author Jeff McDonald
 *
 */
public abstract class B2buaServlet extends AsyncSipServlet
		implements B2buaListener, SipServletListener, ServletContextListener, TimerListener {
	private static final long serialVersionUID = 1L;

	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow;

		if (request.getMethod().equals("INVITE")) {
			if (request.isInitial()) {
				callflow = new InitialInvite(this);
			} else {
				callflow = new Reinvite(this);
			}
		} else if (request.getMethod().equals("BYE")) {
			callflow = new Bye(this);
		} else if (request.getMethod().equals("CANCEL")) {
			callflow = new Cancel(this);
		} else {
			callflow = new Passthru(this);
		}

		return callflow;
	}

	@Override
	public abstract void callStarted(SipServletRequest request) throws ServletException, IOException; 
	
	@Override
	public abstract void callAnswered(SipServletResponse response) throws ServletException, IOException;
	
	@Override
	public abstract void callConnected(SipServletRequest request) throws ServletException, IOException;
	
	@Override
	public abstract void callCompleted(SipServletRequest request) throws ServletException, IOException;
	
	@Override
	public abstract void callDeclined(SipServletResponse response) throws ServletException, IOException;
	
	@Override
	public abstract void callAbandoned(SipServletRequest request) throws ServletException, IOException;
	
}
