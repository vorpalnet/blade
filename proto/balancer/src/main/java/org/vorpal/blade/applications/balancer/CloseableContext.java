package org.vorpal.blade.applications.balancer;

import javax.naming.InitialContext;
import javax.naming.NamingException;

class CloseableContext extends InitialContext implements AutoCloseable {
	CloseableContext() throws NamingException {
		super();
	}
}
