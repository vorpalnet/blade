package org.vorpal.blade.framework.config;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

public class RegExRoute {
	public String key;
	public Matcher matcher;
	public String header;
	public Selector selector;
	
	public Map<String, String> attributes = new HashMap<>();
	
}
