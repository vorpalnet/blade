package org.vorpal.blade.framework.v3.configuration.tables;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/// Exact-match routing table backed by a [HashMap].
public class HashRoutingTable extends RoutingTable implements Serializable {
	private static final long serialVersionUID = 1L;

	public HashRoutingTable() {
		this.entries = new HashMap<>();
	}

	@Override
	protected Map<String, String> lookup(String key) {
		return entries.get(key);
	}
}
