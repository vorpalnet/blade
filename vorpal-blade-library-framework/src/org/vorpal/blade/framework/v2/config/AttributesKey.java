package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class AttributesKey implements Serializable{
	public String key;
	public Map<String, String> attributes = new HashMap<>();
}
