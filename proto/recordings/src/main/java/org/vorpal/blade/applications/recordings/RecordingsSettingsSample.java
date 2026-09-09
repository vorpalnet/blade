package org.vorpal.blade.applications.recordings;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;

import org.vorpal.blade.framework.v3.security.AccessRule;

/// The shipped sample: rules that show the shape without granting anything on a
/// real deployment.
///
/// The group names are obviously placeholders on purpose. A sample that used
/// plausible names would be copied into production unread, and a rule nobody
/// read is a rule nobody meant.
public class RecordingsSettingsSample extends RecordingsSettings {
	private static final long serialVersionUID = 1L;

	public RecordingsSettingsSample() {
		LinkedList<AccessRule> rules = getAccess().getRules();

		// An agent hears their own calls and nobody else's. One rule, no
		// per-user configuration: ${subject.name} is the caller's own name.
		LinkedHashMap<String, String> ownCalls = new LinkedHashMap<>();
		ownCalls.put("agent", "${subject.name}");
		rules.add(new AccessRule("agents hear their own calls", null, ownCalls,
				Arrays.asList("phi:list", "phi:play")));

		// A supervisor hears their own queue. The queue is a record attribute,
		// so this grants nothing outside it.
		LinkedHashMap<String, String> ownQueue = new LinkedHashMap<>();
		ownQueue.put("queue", "EXAMPLE-QUEUE");
		rules.add(new AccessRule("supervisors hear their queue",
				new LinkedList<>(Arrays.asList("EXAMPLE-SUPERVISORS")), ownQueue,
				Arrays.asList("phi:list", "phi:transcript", "phi:play")));

		// A reviewer reads the words of every call and hears none of them. The
		// group is the identity provider's: with the container's OpenID Connect
		// provider, a federated user in a group called Reviewer arrives holding
		// it, and nobody is given an account here to make that so.
		rules.add(new AccessRule("reviewers read every transcript",
				new LinkedList<>(Arrays.asList("Reviewer")), null,
				Arrays.asList("phi:list", "phi:transcript")));

		// Compliance sees everything and may take a copy. Export is its own rung
		// because that is the point where content stops being auditable.
		rules.add(new AccessRule("compliance may export",
				new LinkedList<>(Arrays.asList("EXAMPLE-COMPLIANCE")), null,
				Arrays.asList("phi:list", "phi:transcript", "phi:play", "phi:export", "phi:unredact")));
	}
}
