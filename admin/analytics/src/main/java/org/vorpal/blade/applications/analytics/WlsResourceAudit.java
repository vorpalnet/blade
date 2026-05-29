package org.vorpal.blade.applications.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/// Walks the WebLogic DomainConfiguration MBean tree to detect whether the
/// BLADE analytics pipeline's required JMS and JDBC resources exist.
///
/// Resource set audited (all read-only):
///
/// - **JMS Server** — any `JMSServer` defined under DomainConfiguration
/// - **JMS Module** — any `JMSSystemResource` defined under DomainConfiguration
/// - **Connection Factory** — a `ConnectionFactory` bound to the JNDI name
///   [#EXPECTED_CONNECTION_FACTORY_JNDI]
/// - **Distributed Queue** — a `DistributedQueue` (or plain `Queue`) bound
///   to [#EXPECTED_QUEUE_JNDI]
/// - **JDBC Data Source** — a `JDBCSystemResource` whose data source params
///   bind to [#EXPECTED_DATASOURCE_JNDI]
///
/// Bootstrap pattern matches `[[reference_wls_domain_jmx_bootstrap]]`: we
/// reach DomainConfiguration via `DomainRuntimeServiceMBean` rather than a
/// hardcoded ObjectName.
public class WlsResourceAudit {

	private static final Logger log = Logger.getLogger(WlsResourceAudit.class.getName());

	public static final String EXPECTED_CONNECTION_FACTORY_JNDI = "jms/BladeAnalyticsConnectionFactory";
	public static final String EXPECTED_QUEUE_JNDI = "jms/BladeAnalyticsDistributedQueue";
	public static final String EXPECTED_DATASOURCE_JNDI = "jdbc/BladeAnalytics";

	/// One row of the audit result — a single resource and whether it was found.
	public static final class Finding {
		public final String key;
		public final String label;
		public final boolean present;
		public final String detail;

		public Finding(String key, String label, boolean present, String detail) {
			this.key = key;
			this.label = label;
			this.present = present;
			this.detail = detail;
		}
	}

	public static List<Finding> run() throws Exception {
		List<Finding> out = new ArrayList<>();
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			ObjectName service = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domain = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");
			if (domain == null) {
				throw new IllegalStateException("DomainRuntimeService.DomainConfiguration is null");
			}

			out.add(auditJmsServers(mbs, domain));
			out.add(auditJmsModules(mbs, domain));
			out.add(auditJmsResource(mbs, domain, "ConnectionFactories",
					"Connection Factory", "connectionFactory", EXPECTED_CONNECTION_FACTORY_JNDI));
			out.add(auditJmsResource(mbs, domain, "DistributedQueues",
					"Distributed Queue", "distributedQueue", EXPECTED_QUEUE_JNDI));
			out.add(auditJdbcDataSource(mbs, domain));
		}
		return out;
	}

	private static Finding auditJmsServers(MBeanServer mbs, ObjectName domain) {
		try {
			ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domain, "JMSServers");
			int count = servers == null ? 0 : servers.length;
			List<String> names = new ArrayList<>();
			if (servers != null) {
				for (ObjectName s : servers) {
					names.add(strAttr(mbs, s, "Name"));
				}
			}
			boolean present = count > 0;
			String detail = present
					? (count + " configured: " + String.join(", ", names))
					: "No JMS Server defined in the domain. At least one is required to host the analytics queue.";
			return new Finding("jmsServer", "JMS Server", present, detail);
		} catch (Exception e) {
			return new Finding("jmsServer", "JMS Server", false, "audit failed: " + e.getMessage());
		}
	}

	private static Finding auditJmsModules(MBeanServer mbs, ObjectName domain) {
		try {
			ObjectName[] mods = (ObjectName[]) mbs.getAttribute(domain, "JMSSystemResources");
			int count = mods == null ? 0 : mods.length;
			List<String> names = new ArrayList<>();
			if (mods != null) {
				for (ObjectName m : mods) {
					names.add(strAttr(mbs, m, "Name"));
				}
			}
			boolean present = count > 0;
			String detail = present
					? (count + " configured: " + String.join(", ", names))
					: "No JMS System Module defined. A module is required to contain the Connection Factory and Distributed Queue.";
			return new Finding("jmsModule", "JMS Module", present, detail);
		} catch (Exception e) {
			return new Finding("jmsModule", "JMS Module", false, "audit failed: " + e.getMessage());
		}
	}

	/// Looks across every JMS module's resource list for a destination of the
	/// given attribute name (e.g. "ConnectionFactories" or "DistributedQueues")
	/// whose JNDIName matches `expectedJndi`.
	private static Finding auditJmsResource(MBeanServer mbs, ObjectName domain,
			String resourceAttr, String label, String key, String expectedJndi) {
		try {
			ObjectName[] mods = (ObjectName[]) mbs.getAttribute(domain, "JMSSystemResources");
			if (mods == null || mods.length == 0) {
				return new Finding(key, label, false,
						"No JMS Module to search; expected " + expectedJndi);
			}
			for (ObjectName module : mods) {
				ObjectName res = (ObjectName) mbs.getAttribute(module, "JMSResource");
				if (res == null) continue;
				ObjectName[] items = (ObjectName[]) mbs.getAttribute(res, resourceAttr);
				if (items == null) continue;
				for (ObjectName item : items) {
					String jndi = strAttr(mbs, item, "JNDIName");
					if (expectedJndi.equals(jndi)) {
						String moduleName = strAttr(mbs, module, "Name");
						String itemName = strAttr(mbs, item, "Name");
						return new Finding(key, label, true,
								"found '" + itemName + "' in module '" + moduleName + "' bound to " + expectedJndi);
					}
				}
			}
			return new Finding(key, label, false,
					"No " + label + " bound to JNDI name " + expectedJndi
							+ " in any module. Producers and the MDB consumer both look this up by name.");
		} catch (Exception e) {
			return new Finding(key, label, false, "audit failed: " + e.getMessage());
		}
	}

	private static Finding auditJdbcDataSource(MBeanServer mbs, ObjectName domain) {
		try {
			ObjectName[] sources = (ObjectName[]) mbs.getAttribute(domain, "JDBCSystemResources");
			if (sources == null || sources.length == 0) {
				return new Finding("jdbcDataSource", "JDBC Data Source", false,
						"No JDBC Data Source defined in the domain. Required JNDI name: " + EXPECTED_DATASOURCE_JNDI);
			}
			for (ObjectName sysRes : sources) {
				ObjectName resource = (ObjectName) mbs.getAttribute(sysRes, "JDBCResource");
				if (resource == null) continue;
				ObjectName params = (ObjectName) mbs.getAttribute(resource, "JDBCDataSourceParams");
				if (params == null) continue;
				String[] jndis = (String[]) mbs.getAttribute(params, "JNDINames");
				if (jndis == null) continue;
				if (Arrays.asList(jndis).contains(EXPECTED_DATASOURCE_JNDI)) {
					String resourceName = strAttr(mbs, sysRes, "Name");
					return new Finding("jdbcDataSource", "JDBC Data Source", true,
							"found '" + resourceName + "' bound to " + EXPECTED_DATASOURCE_JNDI);
				}
			}
			return new Finding("jdbcDataSource", "JDBC Data Source", false,
					"No JDBC Data Source bound to " + EXPECTED_DATASOURCE_JNDI
							+ ". The MDB consumer looks this up to persist incoming analytics rows.");
		} catch (Exception e) {
			return new Finding("jdbcDataSource", "JDBC Data Source", false, "audit failed: " + e.getMessage());
		}
	}

	private static String strAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? v.toString() : "";
		} catch (Exception e) {
			return "";
		}
	}
}
