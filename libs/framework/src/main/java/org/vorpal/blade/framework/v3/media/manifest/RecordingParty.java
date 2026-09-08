package org.vorpal.blade.framework.v3.media.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Who a track carries, and who a transcript utterance is attributed to.
///
/// One vocabulary for identity, whether a reader is looking at audio or text. A
/// transcript naming speakers that no track defines is a transcript nobody can
/// line up against a recording.
///
/// ## The id has to outlive a leg
///
/// A party keeps its identity across a transfer, a re-negotiation or a device
/// change, all of which replace the dialog underneath. Keying identity to the
/// leg means a participant becomes two people part way through a conversation
/// and attribution quietly breaks. The id belongs to the person in the call, not
/// to the connection currently carrying them.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingParty {

	private String id;
	private String role;
	private String label;

	public RecordingParty() {
	}

	public RecordingParty(String id, String role, String label) {
		this.id = id;
		this.role = role;
		this.label = label;
	}

	@JsonPropertyDescription("Stable identifier for this party, unchanged across a transfer or re-negotiation.")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	/// What this party is to the conversation, such as caller, agent or
	/// supervisor. Left as free text because the roles a deployment cares about
	/// are the deployment's business, the same reasoning that keeps
	/// classification in a map rather than in fields.
	@JsonPropertyDescription("This party's role, such as caller, agent or supervisor.")
	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	@JsonPropertyDescription("Display name, when one is known.")
	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}
}
