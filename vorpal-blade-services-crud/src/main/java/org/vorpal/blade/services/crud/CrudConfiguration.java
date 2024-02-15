package org.vorpal.blade.services.crud;

import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.framework.config.RouterConfig;

public class CrudConfiguration extends RouterConfig {

	List<RuleSet> ruleSets = new LinkedList<>();

	public CrudConfiguration() {

	}

}
