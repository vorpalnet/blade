package org.vorpal.blade.applications.callflow;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Trace admin app. The tool is read-only — it records live
/// call traces and reads source over federated JMX from the per-app Source
/// MBeans — so besides the inherited metadata this carries only the choice of
/// which engine node serves the source reads.
@SchemaAbout(
		name = "Trace",
		tagline = "Which app in the chain misbehaved?",
		description = "Record a live SIP call across the whole BLADE app chain: arm a rule, place the "
				+ "call, and read the recording — every message each app sent and received, drawn as a "
				+ "sequence diagram and pinned to the exact source line that emitted it, the lambda-based "
				+ "callflow code that runs in this domain. The answer to \"which app in the chain "
				+ "misbehaved,\" down to the line — and shareable as a self-contained snapshot.")
public class CallflowSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String sourceServer = null;

	public CallflowSettings() {
	}

	@JsonPropertyDescription("Name of the server whose Source MBeans serve the gallery (e.g. 'engine0' — "
			+ "a node that carries no call traffic, so browsing never touches the busy engines). "
			+ "Unset: the first server name in lexicographic order is used.")
	public String getSourceServer() {
		return sourceServer;
	}

	public CallflowSettings setSourceServer(String sourceServer) {
		this.sourceServer = sourceServer;
		return this;
	}
}
