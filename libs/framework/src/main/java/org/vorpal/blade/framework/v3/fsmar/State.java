package org.vorpal.blade.framework.v3.fsmar;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.vorpal.blade.framework.v2.config.FormKeyEnum;
import org.vorpal.blade.framework.v2.config.FormSection;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A state in the FSMAR state machine — a node identified by its map key (the
/// state **id**), distinct from the **application** it invokes. Two states with
/// different ids may share the same [#app], so one application can be invoked
/// more than once on a call-path (e.g. a B2BUA per subscriber leg). `"null"` is
/// the entry state for an initial request.
///
/// On entry to a state, its [#selectors] extract values from the request into
/// the routing [Context] (which is carried across hops in the App Router's
/// `stateInfo`, so values accumulate down the call-path). The state's
/// [#triggers] then decide where the request goes, keyed by SIP method.
@JsonPropertyOrder({ "app", "selectors", "triggers" })
@FormSection(title = "Extraction", fields = { "selectors" })
@FormSection(title = "Routing", fields = { "triggers" })
public class State implements Serializable {
	private static final long serialVersionUID = 1L;

	private String app;
	private List<Selector> selectors = new ArrayList<>();
	private HashMap<String, Trigger> triggers = new HashMap<>();

	/// The application this state invokes. Optional: when absent, the state's id
	/// (its map key) is used, so the common one-instance-per-app case needs no
	/// `app` at all. Set it only to invoke the same application from more than
	/// one state — give those states distinct ids and the same `app`.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Application this state invokes; defaults to the state id. Set it to invoke one application from multiple states.")
	public String getApp() {
		return app;
	}

	public State setApp(String app) {
		this.app = app;
		return this;
	}

	/// The application to invoke for this state: [#app] when set, otherwise the
	/// state's own id (passed in, since a State doesn't hold its own map key).
	public String appOrId(String id) {
		return (app != null && !app.isEmpty()) ? app : id;
	}

	/// Selectors run on entry to this state. Each reads a part of the request
	/// (header, requestURI, originIP, body via JSON/XML/SDP, …) and writes
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
	/// into `ctx`. A selector that throws is logged-and-skipped by the caller's
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
