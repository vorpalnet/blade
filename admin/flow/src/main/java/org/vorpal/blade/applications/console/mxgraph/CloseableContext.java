package org.vorpal.blade.applications.console.mxgraph;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/// try-with-resources friendly InitialContext (same helper the portal uses).
class CloseableContext extends InitialContext implements AutoCloseable {
	CloseableContext() throws NamingException {
		super();
	}
}
