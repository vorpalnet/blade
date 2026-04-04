package org.vorpal.blade.services.proxy.block.simple;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SimpleTranslation {
	@JsonPropertyDescription("Identifier for the calling number pattern to match.")
	public String id;

	@JsonPropertyDescription("List of dialed number rules associated with this calling number.")
	public List<SimpleDialed> dialedNumbers = null;

	@JsonPropertyDescription("List of SIP URIs to forward matching calls to.")
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
