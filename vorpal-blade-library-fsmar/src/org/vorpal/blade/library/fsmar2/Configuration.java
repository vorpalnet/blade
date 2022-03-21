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

import org.vorpal.blade.library.fsmar2.State;
import org.vorpal.blade.library.fsmar2.Transition;

public class Configuration extends HashMap<String, State> implements Serializable {

	public State getPreviousState(String name) {
		State previousState = this.get(name);
		if (previousState == null) {
			previousState = new State();
			this.put(name, previousState);
		}
		return previousState;
	}

	/**
	 * Creates a default configuration used to generate the FSMAR2.SAMPLE file.
	 */
	public Configuration() {

		getPreviousState("null").getMethod("REGISTER").getNextState("proxy-registrar");
		getPreviousState("null").getMethod("SUBSCRIBE").getNextState("presence");
		getPreviousState("null").getMethod("PUBLISH").getNextState("presence");
		getPreviousState("null").getMethod("OPTIONS").getNextState("options");
		Transition b2buaTransition = getPreviousState("null").getMethod("INVITE").getNextState("b2bua").addTransition(new Transition());
		b2buaTransition.getComparisonMap("Request-URI").addComparison("uri", "sip:bob@vorpal.net");
		b2buaTransition.getComparisonMap("Request-URI").addComparison("user", "bob");
		b2buaTransition.getComparisonMap("Request-URI").addComparison("host", "vorpal.net");
		b2buaTransition.getComparisonMap("Request-URI").addComparison("matches", "^.*$");
		b2buaTransition.getComparisonMap("From").addComparison("user", "alice");
		
		
		getPreviousState("b2bua").getMethod("INVITE").getNextState("proxy-registrar");
		

//		getPreviousState("app1").getMethod("INVITE").getNextState("app2").addTransition(new Transition()).getComparisonMap("Request-URI")
//		.addComparison("uri", "^.*sip[s]:alice@vorpal.org.*$");
//
//		getPreviousState("app2").getMethod("INVITE").getNextState("app3").addTransition(new Transition()).getComparisonMap("To")
//				.addComparison("address", "^.*<sip[s]:alice@vorpal.org>.*$");
//
//		Transition t3 = getPreviousState("app3").getMethod("INVITE").getNextState("app4").addTransition(new Transition());
//		t3.getComparisonMap("To").addComparison("user", "bob");
//		t3.getComparisonMap("To").addComparison("host", "vorpal.org");
//		t3.getComparisonMap("To").addComparison("loc", "wonderland");
//
//		
//		getPreviousState("app4").getMethod("INVITE").getNextState("app5").addTransition(new Transition())
//				.getComparisonMap("X-Version-Number").addComparison("equals", "2.1.3");
//
//		getPreviousState("app5").getMethod("INVITE").getNextState("app6").addTransition(new Transition())
//				.getComparisonMap("X-Version-Number").addComparison("matches", "^2\\.\\1.*$");
//
//		getPreviousState("app6").getMethod("INVITE").getNextState("app6").addTransition(new Transition())
//				.getComparisonMap("Allow").addComparison("contains", "UPDATE");
//
//		getPreviousState("app7").getMethod("INVITE").getNextState("app8").addTransition(new Transition())
//		.getComparisonMap("Allow").addComparison("includes", "PRACK");
//
//		Transition t7 = getPreviousState("app8").getMethod("INVITE").getNextState("app9").addTransition(new Transition());
//		t7.getComparisonMap("Session-Expires").addComparison("value", "3600");
//		t7.getComparisonMap("Session-Expires").addComparison("refresher", "uac");
//
//		
//		Action a8 = getPreviousState("app9").getMethod("INVITE").getNextState("app10").addTransition(new Transition()).setAction(new Action());
//		a8.originating = "From";
//		
//		Action a9 = getPreviousState("app10").getMethod("INVITE").getNextState("app11").addTransition(new Transition()).setAction(new Action());
//		a9.terminating = "To";
//		
//		Action a11 = getPreviousState("app11").getMethod("INVITE").getNextState("app12").addTransition(new Transition()).setAction(new Action());
//		a11.route = new String[] { "sip:proxy1", "sip:proxy2" };
//		
//		Action a12 = getPreviousState("app12").getMethod("INVITE").getNextState("app13").addTransition(new Transition()).setAction(new Action());
//		a12.route_back = new String[] { "sip:proxy1", "sip:proxy2" };
//
//		Action a13 = getPreviousState("app13").getMethod("INVITE").getNextState("app14").addTransition(new Transition()).setAction(new Action());
//		a13.route_back = new String[] { "sip:proxy1", "sip:proxy2" };
			
	}

}
