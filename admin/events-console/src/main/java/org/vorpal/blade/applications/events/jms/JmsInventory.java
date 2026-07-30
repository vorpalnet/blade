package org.vorpal.blade.applications.events.jms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;

/// Walks the domain's JMS resources and joins them to their live runtime
/// counters.
///
/// **Why this exists.** Nothing in BLADE has ever read a JMS runtime MBean —
/// there is not one reference to `JMSServerRuntimeMBean` or
/// `JMSDestinationRuntimeMBean` anywhere in the product. Queue depth, pending
/// counts, consumer counts and durable-subscription state have simply been
/// invisible, and the only way to find out whether a consumer was keeping up was
/// to read a log.
///
/// **Two trees, joined by name.** The *configuration* tree says what exists and
/// how it is set up; the *runtime* tree says what is happening. They are
/// separate MBean hierarchies and the console needs both, so this walks
/// configuration for structure and runtime for numbers, then joins on the
/// destination name.
///
/// Both come from the DomainRuntime MBean server on the AdminServer, which
/// federates every managed server — the same bootstrap `WlsResourceAudit` uses,
/// and the reason no HTTP call to the engine tier is needed for any of this.
public class JmsInventory {

	private static final String DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	private static final String DOMAIN_RUNTIME_SERVICE =
			"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean";

	/// The destination collections a JMS module can hold. Ordered so the
	/// distributed forms — what a clustered BLADE domain actually uses — come
	/// first.
	private static final String[] DESTINATION_COLLECTIONS = {
			"UniformDistributedQueues", "UniformDistributedTopics",
			"DistributedQueues", "DistributedTopics",
			"Queues", "Topics" };

	/// One row of the destinations table: configuration joined to runtime.
	public static class Destination {
		public String name;
		public String jndiName;
		public String module;
		public String kind;
		public String subDeployment;
		public String quota;
		public boolean distributed;

		/// Live counters, absent when the destination has no runtime presence
		/// (not yet deployed, or its JMS server is down).
		public boolean live;
		public long messagesCurrent;
		public long messagesPending;
		public long messagesHigh;
		public long messagesReceived;
		public long consumersCurrent;
		public long bytesCurrent;
		public String state;
		public boolean paused;
		public List<DurableSubscriber> durableSubscribers = new ArrayList<>();
	}

	/// A durable subscription on a topic, with the selector it actually
	/// subscribed with.
	///
	/// [#selector] is the payoff: comparing it against the catalog's derived
	/// selector detects drift that is otherwise invisible, because a consumer
	/// whose selector does not match simply receives nothing — indistinguishable
	/// from no events being published.
	public static class DurableSubscriber {
		public String subscriptionName;
		public String clientId;
		public String selector;
		public boolean active;
		public long messagesPending;
		public long messagesCurrent;
		public long lastMessageReceived;
	}

	/// A JMS server and the store behind it.
	public static class Server {
		public String name;
		public String persistentStore;
		public String health;
		public long messagesCurrent;
		public long messagesPending;
		public long destinationsCurrent;
		public List<String> targets = new ArrayList<>();
	}

	/// The whole picture in one snapshot.
	public static class Inventory {
		public List<Server> servers = new ArrayList<>();
		public List<Destination> destinations = new ArrayList<>();
		public List<String> modules = new ArrayList<>();
		public List<String> fileStores = new ArrayList<>();
		public String error;
	}

	/// Read the domain's JMS configuration and runtime in one pass.
	///
	/// Never throws: a console that cannot reach JMX should say so in the page,
	/// not return a 500 that tells the operator nothing.
	public static Inventory read() {
		Inventory inventory = new Inventory();
		try (CloseableCtx ctx = new CloseableCtx()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(DOMAIN_RUNTIME);
			ObjectName service = new ObjectName(DOMAIN_RUNTIME_SERVICE);
			ObjectName domain = (ObjectName) mbs.getAttribute(service, "DomainConfiguration");

			Map<String, Destination> byName = readConfiguration(mbs, domain, inventory);
			readRuntime(mbs, service, inventory, byName);

			inventory.destinations.addAll(byName.values());
		} catch (Exception e) {
			inventory.error = String.valueOf(e);
		}
		return inventory;
	}

	/// Walk `DomainConfiguration` for structure: file stores, JMS servers,
	/// modules and every destination each module declares.
	private static Map<String, Destination> readConfiguration(MBeanServer mbs, ObjectName domain, Inventory inventory)
			throws Exception {
		Map<String, Destination> byName = new LinkedHashMap<>();

		for (ObjectName store : objectNames(mbs, domain, "FileStores")) {
			inventory.fileStores.add(str(mbs, store, "Name"));
		}

		for (ObjectName server : objectNames(mbs, domain, "JMSServers")) {
			Server row = new Server();
			row.name = str(mbs, server, "Name");
			ObjectName store = objectName(mbs, server, "PersistentStore");
			row.persistentStore = (store == null) ? null : str(mbs, store, "Name");
			for (ObjectName target : objectNames(mbs, server, "Targets")) {
				row.targets.add(str(mbs, target, "Name"));
			}
			inventory.servers.add(row);
		}

		for (ObjectName module : objectNames(mbs, domain, "JMSSystemResources")) {
			String moduleName = str(mbs, module, "Name");
			inventory.modules.add(moduleName);

			ObjectName resource = objectName(mbs, module, "JMSResource");
			if (resource == null) {
				continue;
			}
			for (String collection : DESTINATION_COLLECTIONS) {
				for (ObjectName destination : objectNames(mbs, resource, collection)) {
					Destination row = new Destination();
					row.name = str(mbs, destination, "Name");
					row.jndiName = str(mbs, destination, "JNDIName");
					row.module = moduleName;
					row.kind = collection;
					row.distributed = collection.startsWith("Uniform") || collection.startsWith("Distributed");
					row.subDeployment = str(mbs, destination, "SubDeploymentName");
					ObjectName quota = objectName(mbs, destination, "Quota");
					row.quota = (quota == null) ? null : str(mbs, quota, "Name");
					byName.put(row.name, row);
				}
			}
		}
		return byName;
	}

	/// Walk every server's `JMSRuntime` for the numbers, joining to the
	/// configuration rows by destination name.
	///
	/// A distributed destination presents one runtime MBean **per member**, so
	/// the counters are summed and the high-water marks maxed — a single row in
	/// the console means the destination as a whole, which is what an operator
	/// is asking about.
	private static void readRuntime(MBeanServer mbs, ObjectName service, Inventory inventory,
			Map<String, Destination> byName) throws Exception {

		Map<String, Server> serversByName = new LinkedHashMap<>();
		for (Server server : inventory.servers) {
			serversByName.put(server.name, server);
		}

		for (ObjectName serverRuntime : objectNames(mbs, service, "ServerRuntimes")) {
			ObjectName jmsRuntime = objectName(mbs, serverRuntime, "JMSRuntime");
			if (jmsRuntime == null) {
				continue;
			}
			for (ObjectName jmsServer : objectNames(mbs, jmsRuntime, "JMSServers")) {
				accumulateServer(mbs, jmsServer, serversByName);
				for (ObjectName destination : objectNames(mbs, jmsServer, "Destinations")) {
					accumulateDestination(mbs, destination, byName);
				}
			}
		}
	}

	private static void accumulateServer(MBeanServer mbs, ObjectName jmsServer, Map<String, Server> serversByName) {
		try {
			Server row = serversByName.get(str(mbs, jmsServer, "Name"));
			if (row == null) {
				return;
			}
			row.messagesCurrent += num(mbs, jmsServer, "MessagesCurrentCount");
			row.messagesPending += num(mbs, jmsServer, "MessagesPendingCount");
			row.destinationsCurrent += num(mbs, jmsServer, "DestinationsCurrentCount");
			Object health = mbs.getAttribute(jmsServer, "HealthState");
			row.health = (health == null) ? null : String.valueOf(health);
		} catch (Exception e) {
			// One unreadable server must not blank the whole inventory.
		}
	}

	private static void accumulateDestination(MBeanServer mbs, ObjectName destination,
			Map<String, Destination> byName) {
		try {
			// A distributed member is named for its parent with a decoration;
			// match the longest configured name the runtime name contains
			// rather than parsing a convention I have not verified.
			String runtimeName = str(mbs, destination, "Name");
			Destination row = matchDestination(runtimeName, byName);
			if (row == null) {
				return;
			}
			row.live = true;
			row.messagesCurrent += num(mbs, destination, "MessagesCurrentCount");
			row.messagesPending += num(mbs, destination, "MessagesPendingCount");
			row.messagesReceived += num(mbs, destination, "MessagesReceivedCount");
			row.messagesHigh = Math.max(row.messagesHigh, num(mbs, destination, "MessagesHighCount"));
			row.consumersCurrent += num(mbs, destination, "ConsumersCurrentCount");
			row.bytesCurrent += num(mbs, destination, "BytesCurrentCount");
			row.state = str(mbs, destination, "State");
			row.paused |= bool(mbs, destination, "ProductionPaused") || bool(mbs, destination, "ConsumptionPaused");

			for (ObjectName subscriber : objectNames(mbs, destination, "DurableSubscribers")) {
				DurableSubscriber sub = new DurableSubscriber();
				sub.subscriptionName = str(mbs, subscriber, "SubscriptionName");
				sub.clientId = str(mbs, subscriber, "ClientID");
				sub.selector = str(mbs, subscriber, "Selector");
				sub.active = bool(mbs, subscriber, "Active");
				sub.messagesPending = num(mbs, subscriber, "MessagesPendingCount");
				sub.messagesCurrent = num(mbs, subscriber, "MessagesCurrentCount");
				sub.lastMessageReceived = num(mbs, subscriber, "LastMessagesReceivedTime");
				row.durableSubscribers.add(sub);
			}
		} catch (Exception e) {
			// Same: skip this member, keep the rest.
		}
	}

	/// Join a runtime destination name to its configured row.
	///
	/// WebLogic decorates a distributed member's runtime name with its module
	/// and hosting server. I have not verified that convention against a live
	/// domain, so this matches by containment and takes the longest match rather
	/// than parsing a format that might differ — a wrong parse would silently
	/// attribute counters to the wrong destination.
	private static Destination matchDestination(String runtimeName, Map<String, Destination> byName) {
		if (runtimeName == null) {
			return null;
		}
		Destination exact = byName.get(runtimeName);
		if (exact != null) {
			return exact;
		}
		Destination best = null;
		for (Map.Entry<String, Destination> entry : byName.entrySet()) {
			if (runtimeName.contains(entry.getKey())
					&& (best == null || entry.getKey().length() > best.name.length())) {
				best = entry.getValue();
			}
		}
		return best;
	}

	// ------------------------------------------------------------- MBean helpers

	private static ObjectName[] objectNames(MBeanServer mbs, ObjectName parent, String attribute) {
		try {
			Object value = mbs.getAttribute(parent, attribute);
			return (value instanceof ObjectName[]) ? (ObjectName[]) value : new ObjectName[0];
		} catch (Exception e) {
			return new ObjectName[0];
		}
	}

	private static ObjectName objectName(MBeanServer mbs, ObjectName parent, String attribute) {
		try {
			Object value = mbs.getAttribute(parent, attribute);
			return (value instanceof ObjectName) ? (ObjectName) value : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String str(MBeanServer mbs, ObjectName on, String attribute) {
		try {
			Object value = mbs.getAttribute(on, attribute);
			return (value == null) ? null : String.valueOf(value);
		} catch (Exception e) {
			return null;
		}
	}

	private static long num(MBeanServer mbs, ObjectName on, String attribute) {
		try {
			Object value = mbs.getAttribute(on, attribute);
			return (value instanceof Number) ? ((Number) value).longValue() : 0L;
		} catch (Exception e) {
			return 0L;
		}
	}

	private static boolean bool(MBeanServer mbs, ObjectName on, String attribute) {
		try {
			Object value = mbs.getAttribute(on, attribute);
			return (value instanceof Boolean) && ((Boolean) value).booleanValue();
		} catch (Exception e) {
			return false;
		}
	}

	private static final class CloseableCtx extends InitialContext implements AutoCloseable {
		CloseableCtx() throws javax.naming.NamingException {
			super();
		}
	}

	private JmsInventory() {
	}
}
