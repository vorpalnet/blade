package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.RequestSelector;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// FSMAR v3 configuration: a finite state machine that routes SIP requests
/// to applications based on pattern matching.
///
/// The state machine is keyed by the previous application name (or `"null"` for
/// initial requests). Each state contains triggers keyed by SIP method, which
/// contain ordered transitions evaluated until the first match.
///
/// Reusable selectors can be defined once in [#selectors] and referenced
/// by name from any SelectorGroup via `selectorRefs`, keeping the routing
/// rules compact and readable.
@JsonPropertyOrder({ "logging", "defaultApplication", "selectors", "states" })
public class AppRouterConfiguration extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String defaultApplication;
	private HashMap<String, RequestSelector> selectors = new HashMap<>();
	private HashMap<String, State> states = new HashMap<>();

	/// Fallback application when no transition matches an initial request.
	@JsonPropertyDescription("Fallback application when no transition matches an initial request")
	public String getDefaultApplication() {
		return defaultApplication;
	}

	public void setDefaultApplication(String defaultApplication) {
		this.defaultApplication = defaultApplication;
	}

	/// Reusable named selectors. Selector groups reference these by name
	/// via `selectorRefs`, avoiding inline duplication in transitions.
	@JsonPropertyDescription("Named selector library. Reference an entry from a SelectorGroup via selectorRefs.")
	public HashMap<String, RequestSelector> getSelectors() {
		return selectors;
	}

	public void setSelectors(HashMap<String, RequestSelector> selectors) {
		this.selectors = selectors;
	}

	/// Adds a named selector to the library and returns it for chaining.
	public RequestSelector addSelector(String name, RequestSelector selector) {
		if (this.selectors == null) {
			this.selectors = new HashMap<>();
		}
		this.selectors.put(name, selector);
		return selector;
	}

	/// Map of previous application names to their routing states.
	/// Use `"null"` as the key for initial requests (no previous application).
	@JsonPropertyDescription("Map of previous application names to routing states. Use 'null' for initial requests.")
	public HashMap<String, State> getStates() {
		return states;
	}

	public void setStates(HashMap<String, State> states) {
		this.states = states;
	}

	/// Gets or creates a state for the given previous application name.
	public State getState(String name) {
		State state = states.get(name);
		if (state == null) {
			state = new State();
			states.put(name, state);
		}
		return state;
	}

}
