package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// AND-composition of [RequestSelector]s used by the FSMAR Transition
/// model. A group matches a request only when every contained selector
/// — inline ([#selectors]) or referenced by name ([#selectorRefs]) —
/// matches. An empty group matches unconditionally. OR logic between
/// alternatives is expressed by placing multiple `SelectorGroup`s on
/// the parent Transition.
///
/// Selector refs allow routing rules to share reusable selectors
/// defined at the top of the config under `selectors`, keeping the
/// transition blocks compact and readable.
@JsonPropertyOrder({ "selectorRefs", "selectors" })
public class SelectorGroup implements Serializable {
	private static final long serialVersionUID = 1L;

	private List<String> selectorRefs;
	private List<RequestSelector> selectors;

	public SelectorGroup() {
	}

	@JsonPropertyDescription("Names of selectors to look up in the top-level `selectors` map; ANDed with inline selectors")
	public List<String> getSelectorRefs() {
		return selectorRefs;
	}

	public void setSelectorRefs(List<String> selectorRefs) {
		this.selectorRefs = selectorRefs;
	}

	public SelectorGroup addSelectorRef(String name) {
		if (this.selectorRefs == null) {
			this.selectorRefs = new ArrayList<>();
		}
		this.selectorRefs.add(name);
		return this;
	}

	@JsonPropertyDescription("Inline selectors ANDed together; all must match for the group to match")
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

	/// Returns true if every inline selector and every referenced selector
	/// matches. An empty/null group matches unconditionally. A named ref
	/// that cannot be resolved in [library] fails the group (defensive —
	/// a typo or missing entry shouldn't silently match everything).
	public boolean matches(SipServletRequest request, Map<String, RequestSelector> library) {
		if ((selectors == null || selectors.isEmpty())
				&& (selectorRefs == null || selectorRefs.isEmpty())) {
			return true;
		}
		if (selectors != null) {
			for (RequestSelector s : selectors) {
				if (!s.matches(request)) return false;
			}
		}
		if (selectorRefs != null) {
			for (String name : selectorRefs) {
				RequestSelector s = (library == null) ? null : library.get(name);
				if (s == null) return false;
				if (!s.matches(request)) return false;
			}
		}
		return true;
	}
}
