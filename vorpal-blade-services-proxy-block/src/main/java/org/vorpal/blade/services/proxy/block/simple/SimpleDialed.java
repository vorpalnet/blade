package org.vorpal.blade.services.proxy.block.simple;

import java.util.LinkedList;
import java.util.List;

public class SimpleDialed {
	public String id;
	public List<String> forwardTo = new LinkedList<>();

	public SimpleDialed() {
		// default constructor
	}
}
