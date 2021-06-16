/**
 *  BLADE - Blended Layer Application Development Environment
 *  Copyright (C) 2018-2021  Vorpal Networks, LLC
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.vorpal.blade.framework.b2bua;

import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public interface B2buaListener {
	public void callStarted(SipServletRequest request) throws Exception;

	public void callAnswered(SipServletResponse response) throws Exception;

	public void callConnected(SipServletRequest request) throws Exception;

	public void callCompleted(SipServletRequest request) throws Exception;

	public void callRefused(SipServletResponse response) throws Exception;

	public void callerEvent(SipServletMessage msg) throws Exception;

	public void calleeEvent(SipServletMessage msg) throws Exception;

	public void b2buaCreated(SipServletContextEvent event) throws Exception;

	public void b2buaDestroyed(SipServletContextEvent event) throws Exception;
}
