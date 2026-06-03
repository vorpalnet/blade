package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.vorpal.blade.framework.v2.config.FormKeyEnum;
import org.vorpal.blade.framework.v2.config.FormSection;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A state in the FSMAR state machine — identified by the previously-invoked
/// application (or `"null"` for an initial request).
///
/// On entry to a state, its [#selectors] extract values from the request into
/// the routing [Context] (which is carried across hops in the App Router's
/// `stateInfo`, so values accumulate down the call-path). The state's
/// [#triggers] then decide where the request goes, keyed by SIP method.
@JsonPropertyOrder({ "selectors", "triggers" })
@FormSection(title = "Extraction", fields = { "selectors" })
@FormSection(title = "Routing", fields = { "triggers" })
public class State implements Serializable {
	private static final long serialVersionUID = 1L;

	private List<Selector> selectors = new ArrayList<>();
	private HashMap<String, Trigger> triggers = new HashMap<>();

	/// Selectors run on entry to this state. Each reads a part of the request
	/// (header, Request-URI, Origin-IP, body via JSON/XML/SDP, …) and writes
	/// named values into the routing context for `when` conditions and `${}`
	/// route templates to use. Values persist across later states.
	@JsonPropertyDescription("Selectors run on entry to this state; each extracts a value from the request into the routing context for conditions and ${} templates.")
	public List<Selector> getSelectors() {
		return selectors;
	}

	public void setSelectors(List<Selector> selectors) {
		this.selectors = (selectors != null) ? selectors : new ArrayList<>();
	}

	public State addSelector(Selector selector) {
		this.selectors.add(selector);
		return this;
	}

	/// Runs every selector against the request, accumulating extracted values
	/// into [ctx]. A selector that throws is logged-and-skipped by the caller's
	/// expectations — extraction failure must never abort routing.
	public void extract(Context ctx, Object payload) {
		if (selectors == null) return;
		for (Selector s : selectors) {
			try {
				s.extract(ctx, payload);
			} catch (Exception e) {
				// Extraction is best-effort; an unmatched/missing field must not
				// break the routing decision. Swallow (matches connector pipeline).
			}
		}
	}

	/// Map of SIP method names to their trigger definitions.
	@JsonPropertyDescription("Map of SIP method names (INVITE, REGISTER, etc.) to trigger definitions")
	@FormKeyEnum({ "INVITE", "REGISTER", "OPTIONS", "SUBSCRIBE", "PUBLISH", "MESSAGE", "NOTIFY", "REFER" })
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
