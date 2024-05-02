package org.vorpal.blade.applications.console.config.test;

import javax.ejb.Remote;

@Remote
public interface HelloBeanRemote {
	public void sayHelloFromServiceBean();
}
