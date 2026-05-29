package org.vorpal.blade.applications.api;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/// Try-with-resources wrapper around [InitialContext] for the federated
/// DomainRuntime MBeanServer lookup. Ported from `admin/portal`.
class CloseableContext extends InitialContext implements AutoCloseable {
	CloseableContext() throws NamingException {
		super();
	}
}
