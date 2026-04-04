package org.vorpal.blade.services.proxy.block.simple;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

@JsonSchemaTitle("Proxy Block")
public class SimpleBlockConfig extends Configuration {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Selector for extracting the calling party address from the From header.")
	public AttributeSelector fromSelector;

	@JsonPropertyDescription("Selector for extracting the called party address from the To header.")
	public AttributeSelector toSelector;

	@JsonPropertyDescription("Selector for extracting the address from the Request-URI.")
	public AttributeSelector ruriSelector;

	@JsonPropertyDescription("List of calling number translation rules for blocking or routing.")
	public List<SimpleTranslation> callingNumbers = new LinkedList<>();

	@JsonPropertyDescription("Default routing rule applied when no calling number match is found.")
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
