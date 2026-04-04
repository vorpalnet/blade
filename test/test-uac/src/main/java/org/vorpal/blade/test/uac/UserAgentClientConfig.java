package org.vorpal.blade.test.uac;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.Configuration;

public class UserAgentClientConfig extends Configuration implements Serializable{

	private static final long serialVersionUID = 1L;
	public Map<String, String> headers = new HashMap<>();

}
