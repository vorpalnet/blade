package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.RouterConfig;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ "logging", "selectors", "ruleSets", "defaultRoute", "maps", "plan" })
public class CrudConfiguration extends RouterConfig implements Serializable {
	private static final long serialVersionUID = 1L;
	public Map<String, RuleSet> ruleSets = new HashMap<>();
//	public List<RuleSet> ruleSets = new LinkedList<>();
}
