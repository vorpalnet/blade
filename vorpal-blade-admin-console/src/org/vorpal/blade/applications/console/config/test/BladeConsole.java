package org.vorpal.blade.applications.console.config.test;

public class BladeConsole implements BladeConsoleMXBean {

	@Override
	public String getJson(String contextName) {
		return "{great: \"googley-moogley\"}";
	}

	@Override
	public void setSampleJson(String contextName, String json) {
		System.out.println("BladeConsole.setSampleJson(" + contextName + ") invoked");
		System.out.println(json);
	}

	@Override
	public void setJsonSchema(String contextName, String jschema) {
		System.out.println("BladeConsole.setJsonSchema(" + contextName + ") invoked");
		System.out.println(jschema);
	}

}
