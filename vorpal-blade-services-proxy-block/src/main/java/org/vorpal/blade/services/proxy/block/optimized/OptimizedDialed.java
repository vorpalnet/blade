package org.vorpal.blade.services.proxy.block.optimized;

import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.services.proxy.block.simple.SimpleDialed;

public class OptimizedDialed {
	public List<String> forwaredTo = new LinkedList<>();

	public OptimizedDialed() {
		// default constructor
	}

	public OptimizedDialed(SimpleDialed that) {
		this.forwaredTo = that.forwaredTo;
	}

}
