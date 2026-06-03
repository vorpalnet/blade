package org.vorpal.blade.applications.analytics;

import java.util.ArrayList;
import java.util.List;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/// Provisions the JMS resources the analytics pipeline needs, in-process, via
/// the WebLogic **Edit** MBean server — the Java/JMX equivalent of the WLST
/// `services/analytics/notes/configure-jms.py` script. Backs the audit page's
/// "fix" button.
///
/// Mirrors that script step-for-step: a file store, a JMS server pinned to it,
/// a JMS system module, a subdeployment targeted at the JMS server, then a
/// connection factory and a uniform distributed queue bound to their JNDI
/// names. All of it is **idempotent** — anything that already exists is left
/// alone — so the button is safe to press whether nothing, or only some, of
/// the stack is present.
///
/// JDBC data-source provisioning is deliberately NOT here: it needs a JDBC URL,
/// driver, username and password (a secret) that no button can supply without
/// operator input. See `configure-mysql.py`.
///
/// Edit-session mechanics (startEdit / save / activate, undo+stopEdit on
/// failure) follow the proven pattern in admin/tuning `WorkManagerSettings`.
final class WlsResourceProvisioner {

	// Resource names — identical to configure-jms.py.
	static final String FILE_STORE = "BladeAnalyticsFileStore";
	static final String FILE_STORE_DIR = "BladeAnalytics";
	static final String JMS_SERVER = "BladeAnalyticsJMSServer";
	static final String MODULE = "BladeAnalyticsSystemModule";
	static final String MODULE_DESCRIPTOR = "BladeAnalytics/BladeAnalytics-jms.xml";
	static final String SUBDEPLOYMENT = "BladeAnalyticsSubdeployment";
	static final String CONNECTION_FACTORY = "BladeAnalyticsConnectionFactory";
	static final String QUEUE = "BladeAnalyticsDistributedQueue";

	private WlsResourceProvisioner() {
	}

	/// Create the JMS stack. Returns a human-readable log of what was created vs.
	/// already present. Throws on any failure (the edit is rolled back first).
	static List<String> provisionJms() throws Exception {
		List<String> log = new ArrayList<>();
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer editMbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/edit");
			ObjectName cfgMgr = new ObjectName(
					"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean");

			editMbs.invoke(cfgMgr, "startEdit", new Object[]{0, 120000}, new String[]{"int", "int"});
			try {
				ObjectName editService = new ObjectName(
						"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean");
				ObjectName domain = (ObjectName) editMbs.getAttribute(editService, "DomainConfiguration");

				ObjectName cluster = resolveTargetCluster(editMbs, domain);
				log.add("Target cluster: " + nameOf(editMbs, cluster));

				// 1. File store (targeted at the cluster).
				ObjectName fileStore = createIfAbsent(editMbs, domain, "FileStores",
						"createFileStore", FILE_STORE, "file store", log);
				editMbs.setAttribute(fileStore, new Attribute("Directory", FILE_STORE_DIR));
				setTargets(editMbs, fileStore, cluster);

				// 2. JMS server, persisted to the file store, on the cluster.
				ObjectName jmsServer = createIfAbsent(editMbs, domain, "JMSServers",
						"createJMSServer", JMS_SERVER, "JMS server", log);
				editMbs.setAttribute(jmsServer, new Attribute("PersistentStore", fileStore));
				setTargets(editMbs, jmsServer, cluster);

				// 3. JMS system module (descriptor + cluster target).
				ObjectName module = findByName(editMbs, domain, "JMSSystemResources", MODULE);
				if (module == null) {
					module = (ObjectName) editMbs.invoke(domain, "createJMSSystemResource",
							new Object[]{MODULE, MODULE_DESCRIPTOR},
							new String[]{"java.lang.String", "java.lang.String"});
					log.add("✓ created JMS module '" + MODULE + "'");
				} else {
					log.add("• JMS module '" + MODULE + "' already exists — skipped");
				}
				setTargets(editMbs, module, cluster);

				// 4. Subdeployment, targeted at the JMS server.
				ObjectName subDeployment = createIfAbsent(editMbs, module, "SubDeployments",
						"createSubDeployment", SUBDEPLOYMENT, "subdeployment", log);
				setTargets(editMbs, subDeployment, jmsServer);

				// The destinations live on the module's JMSResource bean.
				ObjectName jmsResource = (ObjectName) editMbs.getAttribute(module, "JMSResource");

				// 5. Connection factory + JNDI + client/security/transaction params.
				ObjectName cf = createIfAbsent(editMbs, jmsResource, "ConnectionFactories",
						"createConnectionFactory", CONNECTION_FACTORY, "connection factory", log);
				editMbs.setAttribute(cf, new Attribute("JNDIName", WlsResourceAudit.EXPECTED_CONNECTION_FACTORY_JNDI));
				ObjectName security = (ObjectName) editMbs.getAttribute(cf, "SecurityParams");
				editMbs.setAttribute(security, new Attribute("AttachJMSXUserId", false));
				ObjectName client = (ObjectName) editMbs.getAttribute(cf, "ClientParams");
				editMbs.setAttribute(client, new Attribute("ClientIdPolicy", "Restricted"));
				editMbs.setAttribute(client, new Attribute("SubscriptionSharingPolicy", "Exclusive"));
				editMbs.setAttribute(client, new Attribute("MessagesMaximum", 10)); // int attr — must be Integer, not Long
				ObjectName tx = (ObjectName) editMbs.getAttribute(cf, "TransactionParams");
				editMbs.setAttribute(tx, new Attribute("XAConnectionFactoryEnabled", true));
				editMbs.setAttribute(cf, new Attribute("SubDeploymentName", SUBDEPLOYMENT));

				// 6. Uniform distributed queue + JNDI, on the same subdeployment.
				ObjectName queue = createIfAbsent(editMbs, jmsResource, "UniformDistributedQueues",
						"createUniformDistributedQueue", QUEUE, "uniform distributed queue", log);
				editMbs.setAttribute(queue, new Attribute("JNDIName", WlsResourceAudit.EXPECTED_QUEUE_JNDI));
				editMbs.setAttribute(queue, new Attribute("SubDeploymentName", SUBDEPLOYMENT));

				editMbs.invoke(cfgMgr, "save", null, null);
				editMbs.invoke(cfgMgr, "activate", new Object[]{120000L}, new String[]{"long"});
				log.add("Changes saved and activated.");
				return log;

			} catch (Exception e) {
				// Roll the edit session back so a partial failure leaves no lock held.
				try { editMbs.invoke(cfgMgr, "undoUnactivatedChanges", null, null); } catch (Exception ignore) { }
				try { editMbs.invoke(cfgMgr, "stopEdit", null, null); } catch (Exception ignore) { }
				throw e;
			}
		}
	}

	/// The JMS resources must target the engine-tier cluster. configure-jms.py
	/// hardcodes `BEA_ENGINE_TIER_CLUST`; we discover it instead. Exactly one
	/// cluster → use it. Zero or many → refuse rather than guess.
	private static ObjectName resolveTargetCluster(MBeanServer mbs, ObjectName domain) throws Exception {
		ObjectName[] clusters = (ObjectName[]) mbs.getAttribute(domain, "Clusters");
		if (clusters == null || clusters.length == 0) {
			throw new IllegalStateException(
					"No cluster defined in the domain to target the analytics JMS resources at.");
		}
		if (clusters.length > 1) {
			StringBuilder names = new StringBuilder();
			for (ObjectName c : clusters) {
				if (names.length() > 0) names.append(", ");
				names.append(nameOf(mbs, c));
			}
			throw new IllegalStateException("Multiple clusters present (" + names
					+ "); provision the JMS resources manually with configure-jms.py and the intended target.");
		}
		return clusters[0];
	}

	private static ObjectName createIfAbsent(MBeanServer mbs, ObjectName parent, String collectionAttr,
			String createOp, String name, String label, List<String> log) throws Exception {
		ObjectName existing = findByName(mbs, parent, collectionAttr, name);
		if (existing != null) {
			log.add("• " + label + " '" + name + "' already exists — skipped");
			return existing;
		}
		ObjectName created = (ObjectName) mbs.invoke(parent, createOp,
				new Object[]{name}, new String[]{"java.lang.String"});
		log.add("✓ created " + label + " '" + name + "'");
		return created;
	}

	private static ObjectName findByName(MBeanServer mbs, ObjectName parent, String collectionAttr, String name)
			throws Exception {
		Object arr = mbs.getAttribute(parent, collectionAttr);
		if (arr instanceof ObjectName[]) {
			for (ObjectName o : (ObjectName[]) arr) {
				if (name.equals(mbs.getAttribute(o, "Name"))) {
					return o;
				}
			}
		}
		return null;
	}

	private static void setTargets(MBeanServer mbs, ObjectName bean, ObjectName... targets) throws Exception {
		mbs.setAttribute(bean, new Attribute("Targets", targets));
	}

	private static String nameOf(MBeanServer mbs, ObjectName on) {
		try {
			return String.valueOf(mbs.getAttribute(on, "Name"));
		} catch (Exception e) {
			return on.getKeyProperty("Name");
		}
	}
}
