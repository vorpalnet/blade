package com.bea.wcp.sip;

/// Stands in for the SIP container in the agent's tests: it calls the
/// application's code, as it does for every request, so the container is on
/// the stack beneath whatever the application does.
public final class FakeContainer {

	private FakeContainer() {
	}

	public static void dispatch(Runnable application) {
		application.run();
	}
}
