package org.vorpal.blade.services.crud;

import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.config.RouterConfig;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ "logging", "ruleSets", "defaultRoute", "selectors", "maps", "plan" })
public class CrudConfiguration extends RouterConfig {

	public Map<String, RuleSet> ruleSets = new HashMap<>();

	public CrudConfiguration() {

	}

}
