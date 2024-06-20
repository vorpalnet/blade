package org.vorpal.blade.applications.console.config.test;

import javax.annotation.Resource;
import javax.ejb.SessionContext;
import javax.ejb.Stateless;

//@Stateless(name = "HelloWorld", mappedName = "HelloWorld")
public class HelloWorldBean implements HelloWorld {

//	@Resource
//	private SessionContext context;

	@Override
	public String getHelloWorld() {
		System.out.println("Welcome to EJB Tutorial on the AdminServer!");
		return "Welcome to EJB Tutorial!";
	}
}