package org.vorpal.blade.applications.portal;

import javax.naming.InitialContext;
import javax.naming.NamingException;

class CloseableContext extends InitialContext implements AutoCloseable {
	CloseableContext() throws NamingException {
		super();
	}
}
