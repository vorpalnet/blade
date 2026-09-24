package org.vorpal.blade.applications.agent;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Configuration for the Agent Console.
///
/// The console is a converged app: its SIP side pops the human agent's screen as
/// a call arrives and forwards the call to that agent; its web side serves the
/// dashboard and the live WebSocket the pop and the report share. Everything an operator
/// tunes lives here.
@SchemaAbout(
		name = "Agent Console",
		tagline = "Live Screen-Pop and Fraud Reporting for the Human Agent",
		description = "As a call arrives, the agent sees who is calling, the STIR/SHAKEN result, the "
				+ "screening verdict and how often this number has called before, and can report a "
				+ "scam with one click. The report blocks the number and labels the call for the risk score.")
public class AgentSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	private String agentUri;
	private String voicemailUri;
	private String dataSource;
	private String spamTable = "spam_numbers";
	private String historyTable = "BLADE_CONVERSATION";
	private int defaultReportExpiryDays = 7;
	private String reportGroups;
	private int ringSeconds = 20;
	private java.util.LinkedHashMap<String, String> people = new java.util.LinkedHashMap<>();

	public AgentSettings() {
	}

	@JsonPropertyDescription("Where a screened call is forwarded to reach the human agent, e.g. sip:agents@pbx.example.com. Supports ${var}. In production the ACD/CTI decides the agent; this is the demo/default target.")
	public String getAgentUri() {
		return agentUri;
	}

	public void setAgentUri(String agentUri) {
		this.agentUri = agentUri;
	}

	@JsonPropertyDescription("Where a diverted call is sent instead of the agent, e.g. sip:voicemail@pbx.example.com. Supports ${var}.")
	public String getVoicemailUri() {
		return voicemailUri;
	}

	public void setVoicemailUri(String voicemailUri) {
		this.voicemailUri = voicemailUri;
	}

	@JsonPropertyDescription("JNDI name of the JDBC DataSource holding the call catalog and the spam-number list, e.g. jdbc/BladeCatalog. Blank disables history and block-list writes; the pop still shows what the INVITE carries.")
	public String getDataSource() {
		return dataSource;
	}

	public void setDataSource(String dataSource) {
		this.dataSource = dataSource;
	}

	@JsonPropertyDescription("Table an agent report writes a blocked number into (see proxy-block's spam-numbers.sql). Default spam_numbers.")
	public String getSpamTable() {
		return spamTable;
	}

	public void setSpamTable(String spamTable) {
		this.spamTable = spamTable;
	}

	@JsonPropertyDescription("Call-catalog table queried for a caller's history (call count, recent calls). Default BLADE_CONVERSATION.")
	public String getHistoryTable() {
		return historyTable;
	}

	public void setHistoryTable(String historyTable) {
		this.historyTable = historyTable;
	}

	@JsonPropertyDescription("How many days a reported number stays blocked. Numbers rotate, so blocks expire. Default 7.")
	public int getDefaultReportExpiryDays() {
		return defaultReportExpiryDays;
	}

	public void setDefaultReportExpiryDays(int defaultReportExpiryDays) {
		this.defaultReportExpiryDays = defaultReportExpiryDays;
	}

	@JsonPropertyDescription("Comma-separated identity-provider groups whose members may report a call, e.g. \"CallCenterAgent,Supervisor\". Matched against the signed-in identity's group claims. Blank means any signed-in user may report; a user in none of these groups may still watch the console but not report.")
	public String getReportGroups() {
		return reportGroups;
	}

	public void setReportGroups(String reportGroups) {
		this.reportGroups = reportGroups;
	}

	@JsonPropertyDescription("How many seconds to ring the agent before a call with no answer fails over to the voicemail URI. 0 disables the voicemail fallback (ring forever). Default 20.")
	public int getRingSeconds() {
		return ringSeconds;
	}

	public void setRingSeconds(int ringSeconds) {
		this.ringSeconds = ringSeconds;
	}

	@JsonPropertyDescription("Who an agent may bring into a live call, by the name the card shows (Supervisor, Billing, "
			+ "Interpreter) and the SIP address dialled for it. The console sends only the name, so an agent can dial "
			+ "no one else; the listener's partyTargets must allow each address too. Empty hides the control.")
	public java.util.LinkedHashMap<String, String> getPeople() {
		return people;
	}

	public void setPeople(java.util.LinkedHashMap<String, String> people) {
		this.people = people;
	}
}
