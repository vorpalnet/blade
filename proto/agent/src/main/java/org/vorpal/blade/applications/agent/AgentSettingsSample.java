package org.vorpal.blade.applications.agent;

import java.util.ArrayList;
import java.util.List;

import org.vorpal.blade.framework.v2.config.AttributeSelector;

/// Sample Agent Console configuration written to `_samples/` on first deploy.
/// Fictional hosts; an operator replaces the URIs and the data source.
///
/// Two session selectors, `ani` and `dnis`. Where the domain has an event bus and
/// an analytics database, they are what make "called 4 times before" true:
/// every call this app handles becomes a session row keyed by the caller's
/// number, and [Catalog#history] counts those. Without the selectors the pop can only count
/// recorded conversations, and a call centre does not record every call.
public class AgentSettingsSample extends AgentSettings {
	private static final long serialVersionUID = 1L;

	/// Session key ids; [Catalog] queries the analytics store by these names.
	public static final String KEY_ANI = "ani";
	public static final String KEY_DNIS = "dnis";

	public AgentSettingsSample() {
		setAgentUri("sip:agents@pbx.example.com");
		getPeople().put("Supervisor", "sip:supervisor@pbx.example.com");
		setVoicemailUri("sip:voicemail@pbx.example.com");
		setDataSource("jdbc/BladeCatalog");
		setSpamTable("spam_numbers");
		setHistoryTable("BLADE_CONVERSATION");
		setDefaultReportExpiryDays(7);
		setReportGroups("CallCenterAgent,Supervisor");

		// The caller's number from both places it can be asserted: the pop shows
		// the network-asserted one when there is one, so history must be keyed by
		// it too. Both selectors carry the same id; a session gets both keys when
		// both match, and the catalog counts sessions, not keys.
		List<AttributeSelector> keys = new ArrayList<>();
		keys.add(new AttributeSelector(KEY_ANI, "P-Asserted-Identity", "^.*sips?:\\+?1?(\\d{10})@.*$", "$1"));
		keys.add(new AttributeSelector(KEY_ANI, "From", "^.*sips?:\\+?1?(\\d{10})@.*$", "$1"));
		keys.add(new AttributeSelector(KEY_DNIS, "To", "^.*sips?:([^@>;]+)@.*$", "$1"));
		getSession().setSessionSelectors(keys);
	}
}
