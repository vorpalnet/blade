package org.vorpal.blade.applications.events;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/// `InitialContext` that closes itself in try-with-resources. Duplicated in
/// several admin apps rather than shared, because the alternative is a
/// framework dependency for four lines.
class CloseableContext extends InitialContext implements AutoCloseable {
	CloseableContext() throws NamingException {
		super();
	}
}
