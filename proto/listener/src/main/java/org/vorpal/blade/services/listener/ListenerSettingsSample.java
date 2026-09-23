package org.vorpal.blade.services.listener;

import java.util.LinkedList;
import java.util.Arrays;

/// Sample defaults: record and transcribe every call routed here, classified
/// by department, queue and agent; publish what each party says as it is
/// said; and score the caller's voice.
public class ListenerSettingsSample extends ListenerSettings {
	private static final long serialVersionUID = 1L;

	public ListenerSettingsSample() {
		setRecord(true);
		setTranscribe(true);
		setPublishUtterances(true);
		setScoreVoices(new LinkedList<>(Arrays.asList("caller")));
		// Named, not empty. A recording with no attributes matches no rule that
		// names one, so it is reachable only through a rule with an empty match.
		// That is safe, and it teaches nothing about what a policy turns on.
		setRecordAttributes(new LinkedList<>(Arrays.asList("department", "queue", "agent")));
	}
}
