package org.vorpal.blade.framework.v3.configuration.tables;

import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

/// Sorted exact-match routing table backed by a [TreeMap]. Use
/// when entries should iterate in natural key order.
public class TreeRoutingTable extends RoutingTable implements Serializable {
	private static final long serialVersionUID = 1L;

	public TreeRoutingTable() {
		this.entries = new TreeMap<>();
	}

	@Override
	protected Map<String, String> lookup(String key) {
		return entries.get(key);
	}
}
