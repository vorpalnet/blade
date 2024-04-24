package org.vorpal.blade.services.crud2.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.config.RouterConfig;
import org.vorpal.blade.services.crud.RuleSet;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ "logging", "selectors", "ruleSets", "defaultRoute", "maps", "plan" })
public class CrudConfiguration extends RouterConfig implements Serializable {

	public Map<String, RuleSet> ruleSets = new HashMap<>();

	public CrudConfiguration() {
	}

}
