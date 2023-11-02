package org.vorpal.blade.test.b2bua;

import java.io.Serializable;
import java.util.HashMap;

import javax.annotation.Resource;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;

import org.vorpal.blade.framework.logging.LogParameters;
import org.vorpal.blade.framework.logging.LogParametersDefault;

import inet.ipaddr.IPAddressString;

public class ConfigSample extends SampleB2buaConfig implements Serializable {

	public ConfigSample() {

		this.value1 = "this is value 1";
		this.value2 = "this is value 2";

		map = new HashMap<>();

		map.put("one", "this is value one");
		map.put("two", "this is value two");

		LogParameters logParameters = new LogParametersDefault();
		this.setLogging(logParameters);

	}

}
