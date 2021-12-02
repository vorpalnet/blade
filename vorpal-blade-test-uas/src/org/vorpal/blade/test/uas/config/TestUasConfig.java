package org.vorpal.blade.test.uas.config;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestUasConfig {

	private HashMap<String, Integer> errorMap = new HashMap<String, Integer>();

	public TestUasConfig() {
		errorMap.put("18165550404", 404);
		errorMap.put("18165550503", 503);
		errorMap.put("18165550607", 607);
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
