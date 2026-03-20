package org.vorpal.blade.services.proxy.block.simple;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.Configuration;

public class SimpleBlockConfig extends Configuration {
	private static final long serialVersionUID = 1L;

	public AttributeSelector fromSelector;
	public AttributeSelector toSelector;
	public AttributeSelector ruriSelector;
	public List<SimpleTranslation> callingNumbers = new LinkedList<>();
	public SimpleTranslation defaultRoute;
	
	public SimpleBlockConfig() {
		// default constructor
	}

	public AttributeSelector getFromSelector() {
		return fromSelector;
	}

	public void setFromSelector(AttributeSelector fromSelector) {
		this.fromSelector = fromSelector;
	}

	public AttributeSelector getToSelector() {
		return toSelector;
	}

	public void setToSelector(AttributeSelector toSelector) {
		this.toSelector = toSelector;
	}

	public AttributeSelector getRuriSelector() {
		return ruriSelector;
	}

	public void setRuriSelector(AttributeSelector ruriSelector) {
		this.ruriSelector = ruriSelector;
	}

	public SimpleTranslation getDefaultRoute() {
		return defaultRoute;
	}

	public void setDefaultRoute(SimpleTranslation defaultRoute) {
		this.defaultRoute = defaultRoute;
	}

	public List<SimpleTranslation> getCallingNumbers() {
		return callingNumbers;
	}

	public void setCallingNumbers(List<SimpleTranslation> callingNumbers) {
		this.callingNumbers = callingNumbers;
	}

}
