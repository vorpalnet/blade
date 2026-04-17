package org.vorpal.blade.services.irouter;

import java.util.LinkedHashMap;
import java.util.LinkedList;

import org.vorpal.blade.framework.v3.configuration.adapters.SipAdapter;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.tables.HashRoutingTable;
import org.vorpal.blade.framework.v3.configuration.tables.PrefixRoutingTable;

/// Sample iRouter configuration written to `_samples/` on first deploy.
///
/// Demonstrates a minimal pipeline: a [SipAdapter] extracts the user
/// part of the To URI; a [HashRoutingTable] routes named users
/// (sales, support) to their queues; a [PrefixRoutingTable] handles
/// dial-plan prefixes (1800, 1, 44).
public class IRouterConfigSample extends IRouterConfig {
	private static final long serialVersionUID = 1L;

	public IRouterConfigSample() {
		String sipUri = "(?:sips?):(?:(?<user>[^@]+)@)*(?<host>[^:;>]*).*";

		// --- Adapters ---
		SipAdapter sip = new SipAdapter();
		sip.setId("sip");
		sip.setDescription("Extract routing keys from the inbound SIP message");
		sip.addSelector(new RegexSelector("to-user", "To", sipUri, "${user}"));
		this.adapters = new LinkedList<>();
		this.adapters.add(sip);

		// --- Tables ---
		HashRoutingTable named = new HashRoutingTable();
		named.setId("named-routes");
		named.setDescription("Route by dialed user name");
		named.setKeyExpression("${to-user}");
		named.getEntries().put("sales",   entry("requestUri", "sip:sales@queue.example.com"));
		named.getEntries().put("support", entry("requestUri", "sip:support@queue.example.com",
		                                       "X-Priority",  "high"));

		PrefixRoutingTable dialPlan = new PrefixRoutingTable();
		dialPlan.setId("dial-plan");
		dialPlan.setDescription("Route by area code prefix");
		dialPlan.setKeyExpression("${to-user}");
		dialPlan.getEntries().put("1800", entry("requestUri", "sip:tollfree@carrier.example.com"));
		dialPlan.getEntries().put("1",    entry("requestUri", "sip:domestic@carrier.example.com"));
		dialPlan.getEntries().put("44",   entry("requestUri", "sip:uk@intl-carrier.example.com"));

		this.tables = new LinkedList<>();
		this.tables.add(named);
		this.tables.add(dialPlan);

		// --- Default ---
		this.defaultTreatment = entry("requestUri", "sip:operator@pbx.example.com");
	}

	private static LinkedHashMap<String, String> entry(String... pairs) {
		LinkedHashMap<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
		return m;
	}
}
