package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SessionParameters;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// FSMAR v3 configuration: a finite state machine that routes SIP requests to
/// applications based on values extracted from the message.
///
/// The state machine is keyed by the previous application name (or `"null"` for
/// initial requests). Each state's selectors extract values from the request
/// into the routing context, and its triggers (keyed by SIP method) hold
/// ordered transitions evaluated until the first whose `when` condition fires.
@JsonPropertyOrder({ "about", "logging", "defaultApplication", "states" })
public class AppRouterConfiguration extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String defaultApplication;
	private HashMap<String, State> states = new HashMap<>();

	/// Fallback application when no transition matches an initial request.
	@JsonPropertyDescription("Fallback application when no transition matches an initial request")
	public String getDefaultApplication() {
		return defaultApplication;
	}

	public void setDefaultApplication(String defaultApplication) {
		this.defaultApplication = defaultApplication;
	}

	/// FSMAR is an Application Router, not a converged callflow app — it owns no
	/// SIP session to parameterize, so the inherited session block is hidden
	/// from FSMAR's schema, form, and serialized config.
	@JsonIgnore
	@Override
	public SessionParameters getSession() {
		return super.getSession();
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
