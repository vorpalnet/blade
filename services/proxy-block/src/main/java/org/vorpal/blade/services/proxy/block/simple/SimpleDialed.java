package org.vorpal.blade.services.proxy.block.simple;

import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SimpleDialed {
	@JsonPropertyDescription("Identifier for the dialed number pattern to match.")
	public String id;

	@JsonPropertyDescription("List of SIP URIs to forward matching calls to.")
	public List<String> forwardTo = new LinkedList<>();

	public SimpleDialed() {
		// default constructor
	}
}
