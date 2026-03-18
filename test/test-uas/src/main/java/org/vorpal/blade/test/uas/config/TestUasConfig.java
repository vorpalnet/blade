package org.vorpal.blade.test.uas.config;

import java.util.HashMap;

import org.vorpal.blade.framework.v2.config.Configuration;

public class TestUasConfig extends Configuration {

	public HashMap<String, Integer> errorMap = new HashMap<String, Integer>();

	public TestUasConfig() {

	}

	public HashMap<String, Integer> getErrorMap() {
		return errorMap;
	}

	public void setErrorMap(HashMap<String, Integer> errorMap) {
		this.errorMap = errorMap;
	}

	public static void main(String[] args) {

	}

}
