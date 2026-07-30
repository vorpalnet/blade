package org.vorpal.blade.applications.console.tuning;

import java.util.List;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.Context;

/// Finds the WebLogic **Edit** MBeanServer, which is what any configuration change has to go
/// through.
///
/// ## Why this is not just a JNDI lookup
///
/// The documented name is `java:comp/env/jmx/edit`, and it usually works — but it is bound
/// *conditionally*. WebLogic's `MBeanServerDefaultApplicationBindings` builds the three
/// `java:comp/env/jmx/*` names in its constructor and adds each one **only if the corresponding
/// MBeanServer is non-null at that moment**:
///
/// ```
/// getDomainRuntimeMBeanServer(...)  ifnull -> skip
/// getRuntimeMBeanServer(...)        ifnull -> skip
/// getEditMBeanServer(...)           ifnull -> skip
/// ```
///
/// Those bindings are computed once, when the application's component environment is created. This
/// application deploys while the AdminServer is still booting, so if the Edit MBeanServer has not
/// been initialised at that instant, the name is skipped — permanently, for the life of that
/// deployment. The symptom is distinctive and was exactly what we saw: **reads work and writes
/// fail**, because `jmx/domainRuntime` was ready and `jmx/edit` was not, producing
/// `While trying to look up comp/env/jmx/edit in /app/webapp/blade/tuning/...`.
///
/// ## The fallback
///
/// The Edit MBeanServer is an ordinary locally-registered `MBeanServer` in the AdminServer JVM, so
/// when the binding is missing it can still be found by asking which registered server holds the
/// `ConfigurationManagerMBean`. That needs no JNDI entry and no credentials, and it identifies the
/// right server positively rather than by guessing at an index.
final class EditMBeans {

	/// The Edit MBeanServer's configuration manager — used both to drive edit sessions and, here, to
	/// recognise the Edit MBeanServer among the locally registered ones.
	static final String CONFIG_MANAGER =
			"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean";

	private EditMBeans() {
	}

	/// The `ConfigurationManagerMBean` object name.
	static ObjectName configManager() throws Exception {
		return new ObjectName(CONFIG_MANAGER);
	}

	/// Resolve the Edit MBeanServer: the documented JNDI name first, then direct discovery.
	///
	/// @param ctx an application naming context
	/// @throws IllegalStateException if neither route finds it, which means this code is not running
	///         on the AdminServer (managed servers have no Edit MBeanServer) or the domain has
	///         `EditMBeanServerEnabled=false`
	static MBeanServer edit(Context ctx) throws Exception {
		try {
			MBeanServer bound = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			if (bound != null) {
				return bound;
			}
		} catch (javax.naming.NamingException notBound) {
			// Conditional binding was skipped at deployment time; fall through and find it directly.
		}

		ObjectName configManager = configManager();
		List<MBeanServer> registered = javax.management.MBeanServerFactory.findMBeanServer(null);
		for (MBeanServer candidate : registered) {
			try {
				if (candidate.isRegistered(configManager)) {
					return candidate;
				}
			} catch (RuntimeException ignore) {
				// A server that refuses the query is not the one we want.
			}
		}

		throw new IllegalStateException("the WebLogic Edit MBeanServer is not available in this JVM"
				+ " (java:comp/env/jmx/edit is unbound and no registered MBeanServer holds "
				+ "ConfigurationManagerMBean) — configuration changes must run on the AdminServer,"
				+ " and the domain must have EditMBeanServerEnabled=true");
	}
}
