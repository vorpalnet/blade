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

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

public class Condition extends HashMap<String, ComparisonList> implements Serializable {
	public SipApplicationRoutingDirective directive;

	public Condition() {

	}
	
	public Condition(SipApplicationRoutingDirective directive) {
		this.directive = directive;
	}

//	public Condition(String name, ComparisonList comparisonList) {
//		this.put(name, comparisonMap);
//	}

	public boolean checkAll(SipServletRequest request) throws ServletParseException {
		boolean match = true;

		String name;
		ComparisonList comp;
		// Iterate through all comparisons in the condition
		for (Entry<String, ComparisonList> entry : this.entrySet()) {
			name = entry.getKey();
			comp = entry.getValue();

			match = match && comp.check(name, request);

		}

		return match;

	}
}
