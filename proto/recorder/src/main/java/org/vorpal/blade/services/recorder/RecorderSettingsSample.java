package org.vorpal.blade.services.recorder;

import java.util.LinkedList;
import java.util.Arrays;

/// Sample defaults: record every call routed here, classified by department,
/// queue and agent.
public class RecorderSettingsSample extends RecorderSettings {
	private static final long serialVersionUID = 1L;

	public RecorderSettingsSample() {
		setRecord(true);
		// Named, not empty. A recording with no attributes matches no rule that
		// names one, so it is reachable only through a rule with an empty match.
		// That is safe, and it teaches nothing about what a policy turns on.
		setRecordAttributes(new LinkedList<>(Arrays.asList("department", "queue", "agent")));
	}
}
