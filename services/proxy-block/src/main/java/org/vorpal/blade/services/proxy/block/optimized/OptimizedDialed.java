package org.vorpal.blade.services.proxy.block.optimized;

import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.services.proxy.block.simple.SimpleDialed;

public class OptimizedDialed {
	public List<String> forwardTo = new LinkedList<>();

	public OptimizedDialed forwardTo(String sipUri) {
		forwardTo.add(sipUri);
		return this;
	}

	public OptimizedDialed() {
		// default constructor
	}

	public OptimizedDialed(SimpleDialed that) {
		this.forwardTo = that.forwardTo;
	}

}
