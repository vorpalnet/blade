package org.vorpal.blade.services.proxy.block.simple;

import java.util.List;

public class SimpleTranslation {
	public String id;
	public List<SimpleDialed> dialedNumbers = null;
	public List<String> forwardTo;

	public SimpleTranslation() {
		// default constructor
	}

	public List<SimpleDialed> getDialedNumbers() {
		return dialedNumbers;
	}

	public void setDialedNumbers(List<SimpleDialed> dialedNumbers) {
		this.dialedNumbers = dialedNumbers;
	}

	public List<String> getForwardTo() {
		return forwardTo;
	}

	public void setForwardTo(List<String> forwardTo) {
		this.forwardTo = forwardTo;
	}

}
