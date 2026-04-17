package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.HashMap;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Represents a SIP application state in the FSMAR state machine.
///
/// Each state corresponds to a previously-invoked application (or `"null"` for
/// initial requests). Contains a map of SIP method triggers that define routing
/// behavior when a request arrives in this state.
public class State implements Serializable {
	private static final long serialVersionUID = 1L;

	private HashMap<String, Trigger> triggers = new HashMap<>();

	/// Map of SIP method names to their trigger definitions.
	@JsonPropertyDescription("Map of SIP method names (INVITE, REGISTER, etc.) to trigger definitions")
	public HashMap<String, Trigger> getTriggers() {
		return triggers;
	}

	public void setTriggers(HashMap<String, Trigger> triggers) {
		this.triggers = triggers;
	}

	/// Gets or creates a trigger for the given SIP method.
	public Trigger getTrigger(String method) {
		Trigger trigger = triggers.get(method);
		if (trigger == null) {
			trigger = new Trigger();
			triggers.put(method, trigger);
		}
		return trigger;
	}

}
