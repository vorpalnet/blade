package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Container for a lookup key and its associated attributes.
 */
public class AttributesKey implements Serializable {
	private static final long serialVersionUID = 1L;

	public String key;
	public Map<String, String> attributes = new HashMap<>();
}
