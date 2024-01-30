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

package org.vorpal.blade.framework.config;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;

/**
 * Contains a collection (list) of 'Comparisons' associated with a Header (or
 * other Message) attribute. All comparisons are ANDed together. Use separate
 * Conditions if you would like to OR comparisons.
 */
public class Condition extends HashMap<String, ComparisonList> implements Serializable {

	private static final long serialVersionUID = 1L;

	private String id;

	public Condition() {
	}

	public Condition(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public Condition setId(String id) {
		this.id = id;
		return this;
	}

	public Comparison addComparison(String header, String operator, String expression) {
		Comparison comparison;
		ComparisonList list;

		list = this.get(header);
		if (list == null) {
			list = new ComparisonList();
			this.put(header, list);
		}

		comparison = new Comparison(operator, expression);
		list.add(comparison);
		return comparison;
	}

	public boolean checkAll(SipServletRequest request) throws ServletParseException {
		return checkAll(null, request);
	}

	public boolean checkAll(String id, SipServletRequest request) throws ServletParseException {

		boolean match = true;

		String name;
		ComparisonList comp;
		// Iterate through all comparisons in the condition
		for (Entry<String, ComparisonList> entry : this.entrySet()) {
			name = entry.getKey();
			comp = entry.getValue();
			match = match && comp.check(id, name, request);
			if (!match) {
				break;
			}
		}

		return match;

	}
}
