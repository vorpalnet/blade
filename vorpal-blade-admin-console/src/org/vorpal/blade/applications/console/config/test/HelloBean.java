package org.vorpal.blade.applications.console.config.test;

import javax.ejb.Remote;
import javax.ejb.Stateless;

@Stateless
public class HelloBean {
	public void sayHelloFromServiceBean() {
		System.out.println("Hello From Service Bean!");
	}
}