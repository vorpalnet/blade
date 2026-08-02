package org.vorpal.blade.framework.v3.fsmar;

import java.io.Serializable;
import java.util.ArrayList;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// A trigger defines the transitions available for a specific SIP method.
///
/// Transitions are evaluated in order; the first match wins. If no transitions
/// are defined, the trigger implicitly matches with no routing action — and
/// that implicit match COUNTS as a match: the defaultApplication fallback (for
/// "no matches whatsoever") does not fire, and the request routes downstream
/// on its Request-URI, exactly like a matched stop transition.
public class Trigger implements Serializable {
	private static final long serialVersionUID = 1L;

	private ArrayList<Transition> transitions = new ArrayList<>();

	/// Ordered list of transitions; first matching transition wins.
	@JsonPropertyDescription("Ordered list of transitions evaluated sequentially; first match wins")
	public ArrayList<Transition> getTransitions() {
		return transitions;
	}

	public void setTransitions(ArrayList<Transition> transitions) {
		this.transitions = transitions;
	}

	/// Creates a new transition targeting the given application and adds it to this trigger.
	public Transition createTransition(String next) {
		Transition t = new Transition();
		t.setNext(next);
		transitions.add(t);
		return t;
	}

}
