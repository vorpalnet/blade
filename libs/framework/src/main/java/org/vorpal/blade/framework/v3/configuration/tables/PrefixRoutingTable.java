package org.vorpal.blade.framework.v3.configuration.tables;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/// Longest-prefix-match routing table. Walks the input key from
/// full length down to one character, returning the first stored
/// prefix that matches. Useful for telco dial plans.
public class PrefixRoutingTable extends RoutingTable implements Serializable {
	private static final long serialVersionUID = 1L;

	public PrefixRoutingTable() {
		this.entries = new LinkedHashMap<>();
	}

	@Override
	protected Map<String, String> lookup(String key) {
		if (key == null) return null;
		for (int i = key.length(); i > 0; i--) {
			Map<String, String> hit = entries.get(key.substring(0, i));
			if (hit != null) return hit;
		}
		return null;
	}
}
