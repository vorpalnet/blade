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

public class Transition implements Serializable {

	public Condition condition;
	public Action action;

	public Transition() {
	}

	public Transition(Condition condition) {
		this.condition = condition;
	}

	public ComparisonMap getComparisonMap(String name) {
		if (condition == null) {
			condition = new Condition();
		}
		ComparisonMap map = condition.get(name);
		if (map == null) {
			map = new ComparisonMap();
			condition.put(name, map);
		}

		return map;
	}

	public Transition(Action action) {
		this.action = action;
	}

	public Transition(Condition condition, Action action) {
		this.condition = condition;
		this.action = action;
	}

	public Condition getCondition() {
		return condition;
	}

	public Condition setCondition(Condition condition) {
		this.condition = condition;
		return condition;
	}

	public Action getAction() {
		return action;
	}

	public Action setAction(Action action) {
		this.action = action;
		return action;
	}

}
