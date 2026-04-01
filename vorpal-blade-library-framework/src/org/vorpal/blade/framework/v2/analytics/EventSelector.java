package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;

import org.vorpal.blade.framework.v2.config.AttributeSelector;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class EventSelector implements Serializable{
	private static final long serialVersionUID = 1L;
	
	@JsonPropertyDescription("Set of attribute selectors that extract data from SIP messages for this event")
	private Set<AttributeSelector> attributes;

	public EventSelector() {
		attributes = new LinkedHashSet<>();
	}

	public Set<AttributeSelector> getAttributes() {
		return attributes;
	}

	public AttributeSelector addAttribute(String id, String attribute, String pattern, String expression) {
		AttributeSelector as = new AttributeSelector(id, attribute, pattern, expression);
		attributes.add(as);
		return as;
	}

	public EventSelector setAttributes(Set<AttributeSelector> attributes) {
		this.attributes = attributes;
		return this;
	}

	public AttributeSelector addAttribute(AttributeSelector attr) {
		attributes.add(attr);
		return attr;
	}

}
