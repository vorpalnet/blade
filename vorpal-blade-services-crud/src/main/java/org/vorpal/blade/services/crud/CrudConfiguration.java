package org.vorpal.blade.services.crud;

import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.framework.config.RouterConfig;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ "logging", "ruleSets", "defaultRoute", "selectors", "maps", "plan" })
public class CrudConfiguration extends RouterConfig {

	public List<RuleSet> ruleSets = new LinkedList<>();

	public CrudConfiguration() {

	}

}
