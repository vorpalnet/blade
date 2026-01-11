package org.vorpal.blade.services.analytics.sip;

import java.util.LinkedHashSet;
import java.util.Set;

import org.vorpal.blade.framework.v2.config.AttributeSelector;

public class EventSelector {

	private Set<AttributeSelector> attributes;

	public EventSelector() {
		attributes = new LinkedHashSet<>();
	}

	public Set<AttributeSelector> getAttributes() {
		return attributes;
	}

	public EventSelector addAttribute(String id, String attribute, String pattern, String expression) {
		attributes.add(new AttributeSelector(id, attribute, pattern, expression));
		return this;
	}

	public EventSelector setAttributes(Set<AttributeSelector> attributes) {
		this.attributes = attributes;
		return this;
	}

	public EventSelector addAttribute(AttributeSelector attr) {
		attributes.add(attr);
		return this;
	}

}
