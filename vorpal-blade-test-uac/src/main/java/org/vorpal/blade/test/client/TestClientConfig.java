package org.vorpal.blade.test.client;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

public class TestClientConfig extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	public String version = "1.0"; // just a placeholder

	public TestClientConfig() {
	}
}
