package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Container for a regex-matched route with key, header, and extracted attributes.
 */
public class RegExRoute implements Serializable {
	private static final long serialVersionUID = 1L;

	public String key;
	public transient Matcher matcher;
	public String header;
	public Selector selector;
	public Map<String, String> attributes = new HashMap<>();
}
