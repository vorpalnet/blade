package org.vorpal.blade.framework.v3.configuration.tables;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/// Insertion-order exact-match routing table backed by a
/// [LinkedHashMap]. Useful when entry order matters (priority
/// routing, deterministic display in the Configurator).
public class LinkedRoutingTable extends RoutingTable implements Serializable {
	private static final long serialVersionUID = 1L;

	public LinkedRoutingTable() {
		this.entries = new LinkedHashMap<>();
	}

	@Override
	protected Map<String, String> lookup(String key) {
		return entries.get(key);
	}
}
