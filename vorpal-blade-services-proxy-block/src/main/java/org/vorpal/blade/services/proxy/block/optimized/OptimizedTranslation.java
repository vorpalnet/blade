package org.vorpal.blade.services.proxy.block.optimized;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.services.proxy.block.simple.SimpleDialed;
import org.vorpal.blade.services.proxy.block.simple.SimpleTranslation;

public class OptimizedTranslation {
//	public AttributeSelector selector;
	public Map<String, OptimizedDialed> dialedNumbers = null;
	public List<String> forwardTo;

	public OptimizedTranslation() {
		// default constructor
	}

	public OptimizedTranslation(SimpleTranslation that) {

		this.forwardTo = that.forwardTo;

		if (that.dialedNumbers != null && that.dialedNumbers.size() > 0) {
			this.dialedNumbers = new HashMap<>();
			for (SimpleDialed simpleDialed : that.dialedNumbers) {
				this.dialedNumbers.put(simpleDialed.id, new OptimizedDialed(simpleDialed));
			}
		}
	}

}
