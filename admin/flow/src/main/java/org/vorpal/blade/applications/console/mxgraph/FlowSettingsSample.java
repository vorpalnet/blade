package org.vorpal.blade.applications.console.mxgraph;

/// Default values shipped with the WAR. Materializes
/// `./config/custom/vorpal/flow.json` on first deployment when no
/// operator-supplied file is present.
public class FlowSettingsSample extends FlowSettings {
	private static final long serialVersionUID = 1L;

	public FlowSettingsSample() {
		this.about.setName("Flow")
				.setTagline("FSMAR Callflow Editor")
				.setDescription("Design SIP callflows visually as FSMAR state machines. Drag-and-drop trigger / task / transition nodes; round-trips FSMAR XML for execution by the framework's lambda callflow engine.");
	}
}
