package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

public class RegExRoute implements Serializable {
	public String key;
	public Matcher matcher;
	public String header;
	public Selector selector;
	public Map<String, String> attributes = new HashMap<>();
}
