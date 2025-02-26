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

import org.vorpal.blade.framework.v2.config.Condition;

public class Transition implements Serializable {

	private static final long serialVersionUID = 1L;
	public String id;
	public String next;
	public Condition condition;
	public Action action;

	public Transition() {
	}

	public void addComparison(String header, String operator, String expression) {
		if (condition == null) {
			condition = new Condition();
		}

		condition.addComparison(header, operator, expression);
	}

	public Transition setOriginating(String header) {
		if (action == null) {
			action = new Action();
		}
		action.originating = header;
		return this;
	}

	public Transition setTerminating(String header) {
		if (action == null) {
			action = new Action();
		}
		action.terminating = header;
		return this;
	}

	public Transition setRoute(String[] routes) {
		if (action == null) {
			action = new Action();
		}
		action.route = routes;
		return this;
	}

	public Transition setRouteBack(String[] routes) {
		if (action == null) {
			action = new Action();
		}
		action.route_back = routes;

		return this;
	}

	public Transition setRouteFinal(String[] routes) {
		if (action == null) {
			action = new Action();
		}
		action.route_final = routes;

		return this;
	}

	public String getId() {
		return id;
	}

	public Transition setId(String id) {
		this.id = id;
		return this;
	}

}
