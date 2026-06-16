package org.vorpal.blade.library.fsmar3;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SessionParameters;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// FSMAR v3 configuration: a finite state machine that routes SIP requests to
/// applications based on values extracted from the message.
///
/// The state machine is keyed by the previous application name (or `"null"` for
/// initial requests). Each state's selectors extract values from the request
/// into the routing context, and its triggers (keyed by SIP method) hold
/// ordered transitions evaluated until the first whose `when` condition fires.
@JsonPropertyOrder({ "version", "logging", "defaultApplication", "states", "diagram" })
@SchemaAbout(
		name = "FSMAR 3",
		tagline = "Finite State Machine Application Router",
		description = "Routes initial SIP requests between applications using a finite state machine: " + "states keyed by the previous application, selectors that extract values from the " + "message, and transitions matched by conditions over those values. The future of FSMAR.")
public class AppRouterConfiguration extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String defaultApplication;
	private HashMap<String, State> states = new HashMap<>();
	private Diagram diagram;

	/// FSMAR 3 config baseline version. The framework's `version` field
	/// encodes the FSMAR generation for this config lineage: an fsmar config
	/// with no version (or a legacy fsmar2-shaped file) reads as gen 3, so a
	/// future upgrader can recognize a pre-3 file and run the Fsmar2Converter
	/// transform. An explicitly-versioned file keeps its value. Stays
	/// read-only in the Configurator (re-applies the base getter's hints).
	@Override
	@JsonPropertyDescription("Config schema version (framework-managed)")
	@FormLayout(readOnly = true)
	public Integer getVersion() {
		return (version == null) ? 3 : version;
	}

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
		// Coerce null so an explicit "states": null in hand-edited JSON can't
		// NPE the routing loop (same idiom as State.setSelectors).
		this.states = (states != null) ? states : new HashMap<>();
	}

	/// Flow editor layout: state positions, gateway clouds, and which gateway
	/// each null-side transition attaches to. Pure presentation metadata —
	/// routing never reads it, and the config remains valid without it (the
	/// Flow editor auto-lays-out whatever has no stored placement). Absent
	/// entirely until a diagram is saved from the Flow editor. Collapsed and
	/// read-only in the Configurator: operators should see it exists but
	/// edit it only through the Flow editor. See [Diagram].
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@FormLayout(collapsed = true, readOnly = true)
	@JsonPropertyDescription("Flow editor layout (state positions, gateway clouds, transition attachments). Managed by the Flow editor; safe to delete.")
	public Diagram getDiagram() {
		return diagram;
	}

	public void setDiagram(Diagram diagram) {
		this.diagram = diagram;
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
