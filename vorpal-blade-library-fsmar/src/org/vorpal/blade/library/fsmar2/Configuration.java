/**
 *  MIT License
 *  
 *  Copyright (c) 2013, 2022 Vorpal Networks, LLC
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

package org.vorpal.blade.library.fsmar2;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.library.fsmar2.State;
import org.vorpal.blade.library.fsmar2.Transition;

//public class Configuration extends HashMap<String, State> implements Serializable {
public class Configuration implements Serializable {

	public HashMap<String, State> previous = new HashMap<>();

	public State getPrevious(String name) {
		State previousState = previous.get(name);
		if (previousState == null) {
			previousState = new State();
			previous.put(name, previousState);
		}
		return previousState;
	}

	/**
	 * Creates a default configuration used to generate the FSMAR2.SAMPLE file.
	 */
	public Configuration() {

		this.getPrevious("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		this.getPrevious("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getPrevious("null").getTrigger("PUBLISH").createTransition("presence");
		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("INVITE").createTransition("keep-alive");

		Transition t1 = this.getPrevious("keep-alive").getTrigger("INVITE").createTransition("proxy-registrar");
		t1.addComparison("Request-URI", "uri", "^sip[s]:.*$");
		t1.addComparison("From", "address", "^.*<sip[s]:.*>$");
		t1.addComparison("To", "user", "bob");
		t1.addComparison("To", "host", "vorpal.net");
		t1.addComparison("To", "equals", "<sip:bob@vorpal.net>");
		t1.addComparison("Allow", "contains", "INV");
		t1.addComparison("Allow", "includes", "INVITE");
		t1.addComparison("Session-Expires", "value", "3600");
		t1.addComparison("Session-Expires", "refresher", "uac");
		t1.condition.directive = SipApplicationRoutingDirective.NEW;		
		t1.setOriginating("From");
		t1.setRoute(new String[] { "sip:proxy1", "sip:proxy2" });
		
		this.getPrevious("keep-alive").getTrigger("INVITE").createTransition("b2bua");
		this.getPrevious("b2bua").getTrigger("INVITE").createTransition("proxy-registrar");
		
		

	}

}
