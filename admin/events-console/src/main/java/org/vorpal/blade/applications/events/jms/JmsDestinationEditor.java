package org.vorpal.blade.applications.events.jms;

import java.util.Arrays;
import java.util.List;

import javax.management.ObjectName;

/// Creates, tunes and destroys JMS destinations and the stack that hosts them.
///
/// **Adopt, never rename.** WebLogic has no rename operation for a JMS resource,
/// so renaming means destroy-and-create — which orphans the file store's
/// persistent messages. [#ensureStack] therefore uses whatever module already
/// exists and only creates one when there is none. A domain that grew up with
/// the analytics stack gets the event topic added to *it*; a fresh domain gets a
/// single canonical stack. Either way one store, one server, one module, one
/// subdeployment.
public final class JmsDestinationEditor {

	/// Names used when standing a stack up from nothing.
	public static final String STORE = "BladeMessagingFileStore";
	public static final String STORE_DIR = "BladeMessaging";
	public static final String SERVER = "BladeMessagingJMSServer";
	public static final String MODULE = "BladeMessagingSystemModule";
	public static final String DESCRIPTOR = "BladeMessaging/BladeMessaging-jms.xml";
	public static final String SUBDEPLOYMENT = "BladeMessagingSubdeployment";

	/// Modules already in the field, preferred over creating a new one.
	private static final List<String> ADOPTABLE = Arrays.asList(MODULE, "BladeAnalyticsSystemModule",
			"BladeEventBusSystemModule");

	/// The collection and operations for each destination kind the console
	/// offers. Distributed forms only — a clustered BLADE domain has no use for
	/// a singleton destination, and offering one invites a target that will not
	/// fail over.
	public enum Kind {
		QUEUE("UniformDistributedQueues", "createUniformDistributedQueue", "destroyUniformDistributedQueue"),
		TOPIC("UniformDistributedTopics", "createUniformDistributedTopic", "destroyUniformDistributedTopic");

		public final String collection;
		public final String createOp;
		public final String destroyOp;

		Kind(String collection, String createOp, String destroyOp) {
			this.collection = collection;
			this.createOp = createOp;
			this.destroyOp = destroyOp;
		}
	}

	private JmsDestinationEditor() {
	}

	/// Ensure a usable stack exists and return its `JMSResource` bean, which is
	/// where destinations and connection factories live.
	public static ObjectName ensureStack(JmsEditSession session, List<String> targetNames) throws Exception {
		ObjectName[] targets = session.resolveTargets(targetNames);

		ObjectName module = null;
		String moduleName = null;
		for (String candidate : ADOPTABLE) {
			module = session.findByName(session.domain(), "JMSSystemResources", candidate);
			if (module != null) {
				moduleName = candidate;
				session.note("• adopting the existing JMS module '" + candidate
						+ "' — nothing is renamed, because WebLogic cannot rename a JMS resource "
						+ "and destroy-and-recreate would orphan its file store");
				break;
			}
		}

		if (module == null) {
			ObjectName store = session.createIfAbsent(session.domain(), "FileStores", "createFileStore", STORE,
					"file store");
			session.setAttribute(store, "Directory", STORE_DIR);
			session.setTargets(store, targets);

			ObjectName server = session.createIfAbsent(session.domain(), "JMSServers", "createJMSServer", SERVER,
					"JMS server");
			session.server().setAttribute(server, new javax.management.Attribute("PersistentStore", store));
			session.setTargets(server, targets);

			module = (ObjectName) session.server().invoke(session.domain(), "createJMSSystemResource",
					new Object[] { MODULE, DESCRIPTOR },
					new String[] { "java.lang.String", "java.lang.String" });
			session.note("✓ created JMS module '" + MODULE + "'");
			session.setTargets(module, targets);
			moduleName = MODULE;

			ObjectName subDeployment = session.createIfAbsent(module, "SubDeployments", "createSubDeployment",
					SUBDEPLOYMENT, "subdeployment");
			session.setTargets(subDeployment, server);
		}

		session.note("Working in module '" + moduleName + "'.");
		return (ObjectName) session.server().getAttribute(module, "JMSResource");
	}

	/// The subdeployment name to bind a new destination to — whichever the
	/// adopted module already uses, so a destination lands on the same JMS
	/// server as everything else in that module.
	public static String subDeploymentOf(JmsEditSession session, ObjectName resource) throws Exception {
		Object array = session.server().getAttribute(resource, "UniformDistributedQueues");
		String existing = firstSubDeployment(session, array);
		if (existing != null) {
			return existing;
		}
		existing = firstSubDeployment(session, session.server().getAttribute(resource, "UniformDistributedTopics"));
		return (existing != null) ? existing : SUBDEPLOYMENT;
	}

	private static String firstSubDeployment(JmsEditSession session, Object array) throws Exception {
		if (array instanceof ObjectName[]) {
			for (ObjectName destination : (ObjectName[]) array) {
				Object name = session.server().getAttribute(destination, "SubDeploymentName");
				if (name != null && !String.valueOf(name).isEmpty()) {
					return String.valueOf(name);
				}
			}
		}
		return null;
	}

	/// Create a destination, or return the existing one untouched.
	public static ObjectName createDestination(JmsEditSession session, ObjectName resource, Kind kind, String name,
			String jndiName, String subDeployment) throws Exception {
		ObjectName destination = session.createIfAbsent(resource, kind.collection, kind.createOp, name,
				kind.name().toLowerCase());
		session.setAttribute(destination, "JNDIName", jndiName);
		session.setAttribute(destination, "SubDeploymentName", subDeployment);
		return destination;
	}

	/// Give a destination its own byte quota, so one destination cannot fill a
	/// shared file store and starve every other one on it. This is the single
	/// thing a merged stack needs that two separate stacks got for free.
	public static void applyQuota(JmsEditSession session, ObjectName resource, ObjectName destination, String name,
			long bytesMaximum) throws Exception {
		ObjectName quota = session.createIfAbsent(resource, "Quotas", "createQuota", name, "quota");
		session.setAttribute(quota, "BytesMaximum", Long.valueOf(bytesMaximum));
		session.setAttribute(quota, "Shared", Boolean.FALSE);
		session.server().setAttribute(destination, new javax.management.Attribute("Quota", quota));
		session.note("✓ quota '" + name + "' set to " + bytesMaximum + " bytes");
	}

	/// Set the redelivery limit and error destination.
	///
	/// This is what replaces silently swallowing a poison message: after enough
	/// failed deliveries the message lands somewhere an operator can look at it,
	/// rather than being consumed to break a redelivery loop.
	public static void applyDeliveryFailure(JmsEditSession session, ObjectName destination, Integer redeliveryLimit,
			ObjectName errorDestination) throws Exception {
		ObjectName params = (ObjectName) session.server().getAttribute(destination, "DeliveryFailureParams");
		if (redeliveryLimit != null) {
			session.setAttribute(params, "RedeliveryLimit", redeliveryLimit);
		}
		if (errorDestination != null) {
			session.server().setAttribute(params,
					new javax.management.Attribute("ErrorDestination", errorDestination));
		}
		session.note("✓ delivery-failure handling updated");
	}

	/// Set the byte and message thresholds that drive WebLogic's own warnings.
	public static void applyThresholds(JmsEditSession session, ObjectName destination, Long bytesHigh, Long bytesLow,
			Long messagesHigh, Long messagesLow) throws Exception {
		ObjectName thresholds = (ObjectName) session.server().getAttribute(destination, "Thresholds");
		if (bytesHigh != null) {
			session.setAttribute(thresholds, "BytesHigh", bytesHigh);
		}
		if (bytesLow != null) {
			session.setAttribute(thresholds, "BytesLow", bytesLow);
		}
		if (messagesHigh != null) {
			session.setAttribute(thresholds, "MessagesHigh", messagesHigh);
		}
		if (messagesLow != null) {
			session.setAttribute(thresholds, "MessagesLow", messagesLow);
		}
		session.note("✓ thresholds updated");
	}

	/// Destroy a destination.
	///
	/// The caller is responsible for having checked that it is not protected and
	/// that the operator saw its pending count first — this method does what it
	/// is told.
	public static void destroyDestination(JmsEditSession session, ObjectName resource, Kind kind, String name)
			throws Exception {
		ObjectName destination = session.findByName(resource, kind.collection, name);
		if (destination == null) {
			session.note("• " + name + " does not exist — nothing to destroy");
			return;
		}
		session.destroy(resource, kind.destroyOp, destination, kind.name().toLowerCase() + " '" + name + "'");
	}

	/// Find a destination across both distributed collections, for callers that
	/// know a name but not a kind.
	public static ObjectName findDestination(JmsEditSession session, ObjectName resource, String name)
			throws Exception {
		for (Kind kind : Kind.values()) {
			ObjectName found = session.findByName(resource, kind.collection, name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
