// Gumball Agent — proto. MIT License, (c) 2026 Vorpal Networks, LLC.
package org.vorpal.blade.proto.gumball.agent;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SchemaTitle;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@SchemaTitle("Gumball Agent")
public class AgentSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String kurentoUrl;
	protected String bridgeMode;
	protected String systemPrompt;
	protected String language;
	protected String transferTarget;

	@JsonPropertyDescription("WebSocket URL of the Kurento media node, e.g. ws://media-1:8888/kurento")
	public String getKurentoUrl() {
		return kurentoUrl;
	}

	public void setKurentoUrl(String kurentoUrl) {
		this.kurentoUrl = kurentoUrl;
	}

	@JsonPropertyDescription("Media bridge mode: 'loopback' echoes the caller's audio back through "
			+ "a stock Kurento (rig test, no AI/custom module needed); 'agent' wires the custom "
			+ "GumballAgentBridge element to the int8 inference pipeline (real Demo 1).")
	public String getBridgeMode() {
		return bridgeMode;
	}

	public void setBridgeMode(String bridgeMode) {
		this.bridgeMode = bridgeMode;
	}

	@JsonPropertyDescription("System prompt defining the AI agent's persona and task")
	public String getSystemPrompt() {
		return systemPrompt;
	}

	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	@JsonPropertyDescription("BCP-47 language tag for ASR/TTS, e.g. en-US")
	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}

	@JsonPropertyDescription("SIP URI to bridge to when the agent escalates to a human, e.g. sip:queue@example.com")
	public String getTransferTarget() {
		return transferTarget;
	}

	public void setTransferTarget(String transferTarget) {
		this.transferTarget = transferTarget;
	}

}
