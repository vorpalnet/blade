package org.vorpal.blade.applications.agent;

/// Sample Agent Console configuration written to `_samples/` on first deploy.
/// Fictional hosts; an operator replaces the URIs and the data source.
public class AgentSettingsSample extends AgentSettings {
	private static final long serialVersionUID = 1L;

	public AgentSettingsSample() {
		setAgentUri("sip:agents@pbx.example.com");
		setVoicemailUri("sip:voicemail@pbx.example.com");
		setDataSource("jdbc/BladeCatalog");
		setSpamTable("spam_numbers");
		setHistoryTable("BLADE_CONVERSATION");
		setDefaultReportExpiryDays(7);
		setReportGroups("CallCenterAgent,Supervisor");
	}
}
