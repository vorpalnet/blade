package org.vorpal.blade.test.uas.config;

import java.util.ArrayList;

public class TestUasState {

	private String state;
	private String delay;
	private ArrayList<TestUasHeaders> headers;

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getDelay() {
		return delay;
	}

	public void setDelay(String delay) {
		this.delay = delay;
	}

	public ArrayList<TestUasHeaders> getHeaders() {
		return headers;
	}

	public void setHeaders(ArrayList<TestUasHeaders> headers) {
		this.headers = headers;
	}

	public long getDelayInMilliseconds() {
		String tmpState;
		long milliseconds = 0;

		if (state.contains("ms")) {
			tmpState = state.replace("ms", "");
			milliseconds = Long.parseLong(tmpState);
		} else if (state.contains("s")) {
			tmpState = state.replace("s", "");
			milliseconds = Long.parseLong(tmpState) * 1000;
		} else if (state.contains("m")) {
			tmpState = state.replace("m", "");
			milliseconds = Long.parseLong(tmpState) * 1000 * 60;
		} else if (state.contains("h")) {
			tmpState = state.replace("h", "");
			milliseconds = Long.parseLong(tmpState) * 1000 * 60 * 60;
		} else {
			milliseconds = Long.parseLong(state);
		}

		return milliseconds;

	}

}
