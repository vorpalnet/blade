package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Flow editor marker that a state is an ingress entry point (an external SIP
/// entity — SBC, trunk, carrier). Pure presentation metadata in
/// [Diagram#getIngresses]: the ingress is a real FSMAR state with its own
/// selectors and transitions, and inbound traffic reaches it via a generated
/// source-dispatch transition on `"null"` whose `when` is [#getMatch]. The
/// AppRouter never reads this; it only tells the editor to render the state
/// as an ingress cloud and to recognize its dispatch transition as plumbing.
public class Ingress implements Serializable {
	private static final long serialVersionUID = 1L;

	private String match;

	public Ingress() {
	}

	public Ingress(String match) {
		this.match = match;
	}

	/// Source-match `when` expression (e.g. `${originIP} insubnet '10.20.0.0/16'`)
	/// the editor compiles into this ingress's dispatch transition on `"null"`.
	@JsonPropertyDescription("Source-match 'when' expression that classifies inbound traffic into this ingress")
	public String getMatch() {
		return match;
	}

	public Ingress setMatch(String match) {
		this.match = match;
		return this;
	}

}
