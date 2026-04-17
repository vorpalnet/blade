package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// AND-composition of [RequestSelector]s used by the FSMAR Transition
/// model. A group matches a request only when every selector in it
/// matches. An empty group matches unconditionally. OR logic between
/// alternatives is expressed by placing multiple `SelectorGroup`s on
/// the parent Transition.
public class SelectorGroup implements Serializable {
	private static final long serialVersionUID = 1L;

	private List<RequestSelector> selectors;

	public SelectorGroup() {
	}

	@JsonPropertyDescription("Selectors ANDed together; all must match for the group to match")
	public List<RequestSelector> getSelectors() {
		return selectors;
	}

	public void setSelectors(List<RequestSelector> selectors) {
		this.selectors = selectors;
	}

	public SelectorGroup addSelector(RequestSelector selector) {
		if (this.selectors == null) {
			this.selectors = new ArrayList<>();
		}
		this.selectors.add(selector);
		return this;
	}

	/// Returns true if every contained selector matches the request.
	/// An empty or null selector list matches unconditionally.
	public boolean matches(SipServletRequest request) {
		if (selectors == null || selectors.isEmpty()) {
			return true;
		}
		for (RequestSelector s : selectors) {
			if (!s.matches(request)) {
				return false;
			}
		}
		return true;
	}
}
