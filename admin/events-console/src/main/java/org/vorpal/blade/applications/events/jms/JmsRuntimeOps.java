package org.vorpal.blade.applications.events.jms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.naming.InitialContext;

/// Runtime operations on a live destination: pause, resume, purge and browse.
///
/// These act on the **runtime** MBean tree, not configuration — nothing here
/// needs an edit session, and nothing here survives a restart. A distributed
/// destination has one runtime MBean per member, so every operation is applied
/// to each member and the results are summed; pausing "the destination" means
/// pausing it everywhere, which is what an operator means by it.
public final class JmsRuntimeOps {

	private static final String DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	private static final String DOMAIN_RUNTIME_SERVICE =
			"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean";

	/// What a browse returns: a page of messages plus how many there are in
	/// total.
	public static class Page {
		public long total;
		public List<Map<String, Object>> messages = new ArrayList<>();
		public String error;
	}

	private JmsRuntimeOps() {
	}

	/// Apply a no-argument runtime operation to every member of a destination.
	///
	/// @param destinationName the configured destination name
	/// @param operation       `pause`, `resume`, `pauseProduction`,
	///                        `resumeProduction`, `pauseConsumption`,
	///                        `resumeConsumption`, `pauseInsertion`,
	///                        `resumeInsertion`
	/// @return one line per member describing what happened
	public static List<String> apply(String destinationName, String operation) throws Exception {
		List<String> log = new ArrayList<>();
		try (Ctx ctx = new Ctx()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(DOMAIN_RUNTIME);
			for (ObjectName member : members(mbs, destinationName)) {
				try {
					mbs.invoke(member, operation, null, null);
					log.add("✓ " + operation + " on " + member.getKeyProperty("Name"));
				} catch (Exception e) {
					log.add("✕ " + operation + " on " + member.getKeyProperty("Name") + ": " + e.getMessage());
				}
			}
		}
		if (log.isEmpty()) {
			log.add("No running member of '" + destinationName + "' found. Nothing to do.");
		}
		return log;
	}

	/// Delete messages matching a selector, across every member.
	///
	/// An empty selector means every message. WebLogic's own semantics for a
	/// null versus empty selector here are not something I could confirm, so an
	/// empty string is passed deliberately rather than null — and the caller is
	/// expected to have made the operator confirm, because this is irreversible.
	public static long purge(String destinationName, String selector) throws Exception {
		long deleted = 0;
		String effective = (selector == null) ? "" : selector;
		try (Ctx ctx = new Ctx()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(DOMAIN_RUNTIME);
			for (ObjectName member : members(mbs, destinationName)) {
				Object count = mbs.invoke(member, "deleteMessages", new Object[] { effective },
						new String[] { "java.lang.String" });
				if (count instanceof Number) {
					deleted += ((Number) count).longValue();
				}
			}
		}
		return deleted;
	}

	/// Browse messages without consuming them.
	///
	/// Non-destructive: the cursor API reads what is at rest and needs no
	/// consumer, so this cannot steal a message from the real one. That is why
	/// browse is offered on queues while a live *tap* is not — a consuming tap
	/// on a queue would take messages the actual consumer needed.
	///
	/// The cursor is always closed, including on failure: cursors accumulate on
	/// the engine node otherwise.
	public static Page browse(String destinationName, String selector, int start, int count) {
		Page page = new Page();
		try (Ctx ctx = new Ctx()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(DOMAIN_RUNTIME);
			for (ObjectName member : members(mbs, destinationName)) {
				String cursor = null;
				try {
					cursor = (String) mbs.invoke(member, "getMessages",
							new Object[] { (selector == null) ? "" : selector, Integer.valueOf(30) },
							new String[] { "java.lang.String", "java.lang.Integer" });
					if (cursor == null) {
						continue;
					}
					Object size = mbs.invoke(member, "getCursorSize", new Object[] { cursor },
							new String[] { "java.lang.String" });
					if (size instanceof Number) {
						page.total += ((Number) size).longValue();
					}
					Object items = mbs.invoke(member, "getItems",
							new Object[] { cursor, Long.valueOf(start), Integer.valueOf(count) },
							new String[] { "java.lang.String", "java.lang.Long", "java.lang.Integer" });
					collect(items, page);
				} catch (Exception e) {
					page.error = String.valueOf(e);
				} finally {
					closeCursor(mbs, member, cursor);
				}
			}
		} catch (Exception e) {
			page.error = String.valueOf(e);
		}
		return page;
	}

	private static void closeCursor(MBeanServer mbs, ObjectName member, String cursor) {
		if (cursor == null) {
			return;
		}
		try {
			mbs.invoke(member, "closeCursor", new Object[] { cursor }, new String[] { "java.lang.String" });
		} catch (Exception ignore) {
			// Nothing useful to do; it times out on its own.
		}
	}

	private static void collect(Object items, Page page) {
		if (!(items instanceof CompositeData[])) {
			return;
		}
		for (CompositeData item : (CompositeData[]) items) {
			Map<String, Object> row = new LinkedHashMap<>();
			for (String key : item.getCompositeType().keySet()) {
				Object value = item.get(key);
				// Keep the payload readable and bounded: a message body can be
				// large, and this is a browse, not an export.
				if (value instanceof byte[]) {
					value = "(" + ((byte[]) value).length + " bytes)";
				} else if (value != null) {
					String text = String.valueOf(value);
					value = (text.length() > 4096) ? text.substring(0, 4096) + "… (truncated)" : text;
				}
				row.put(key, value);
			}
			page.messages.add(row);
		}
	}

	/// Purge one durable subscription's backlog, leaving the subscription
	/// itself in place.
	///
	/// **There is deliberately no `deleteSubscription` beside this, and the
	/// reason is not laziness.** The drift check reports an orphaned
	/// subscription — one left behind by renaming a subscription or changing its
	/// selector, which does not move the old one, it abandons it — and the
	/// obvious next step is an unsubscribe. It is not that simple here.
	///
	/// Every consumer BLADE generates is `One-Copy-Per-Application` on a
	/// distributed topic, and on that path WebLogic's MDB container
	/// (`JMSConnectionPoller`) creates the connection with
	/// `CLIENT_ID_POLICY_UNRESTRICTED`, overriding the connection factory. Oracle
	/// is explicit about the consequence: *"Durable subscriptions created using
	/// an Unrestricted Client Id must be unsubscribed using
	/// `weblogic.jms.extensions.WLSession.unsubscribe(Topic topic, String name)`,
	/// instead of `javax.jms.Session.unsubscribe(String name)`."*
	///
	/// So the standard JMS call will not remove one of ours. Whoever writes this
	/// needs a `WLSession` and the `Topic` object, not just a name — and should
	/// verify it against a live domain before it ships, because getting it wrong
	/// looks like success and leaves the subscription accruing.
	///
	/// Until then, purging bounds the damage: an orphan stops consuming quota
	/// even though it keeps existing.
	public static List<String> purgeSubscription(String destinationName, String subscriptionName) throws Exception {
		List<String> log = new ArrayList<>();
		try (Ctx ctx = new Ctx()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup(DOMAIN_RUNTIME);
			for (ObjectName member : members(mbs, destinationName)) {
				Object subs = mbs.getAttribute(member, "DurableSubscribers");
				if (!(subs instanceof ObjectName[])) {
					continue;
				}
				for (ObjectName sub : (ObjectName[]) subs) {
					if (subscriptionName.equals(mbs.getAttribute(sub, "SubscriptionName"))) {
						mbs.invoke(sub, "purge", null, null);
						log.add("✓ purged subscription '" + subscriptionName + "' on "
								+ member.getKeyProperty("Name"));
					}
				}
			}
		}
		if (log.isEmpty()) {
			log.add("No subscription named '" + subscriptionName + "' found on '" + destinationName + "'.");
		}
		return log;
	}

	/// Every runtime MBean belonging to a configured destination.
	///
	/// A distributed destination presents one per member, decorated with its
	/// module and hosting server. The decoration format is matched by
	/// containment rather than parsed, because a wrong parse would silently act
	/// on the wrong destination — and these operations are destructive.
	private static List<ObjectName> members(MBeanServer mbs, String destinationName) throws Exception {
		List<ObjectName> found = new ArrayList<>();
		ObjectName service = new ObjectName(DOMAIN_RUNTIME_SERVICE);
		Object servers = mbs.getAttribute(service, "ServerRuntimes");
		if (!(servers instanceof ObjectName[])) {
			return found;
		}
		for (ObjectName server : (ObjectName[]) servers) {
			Object jmsRuntime = mbs.getAttribute(server, "JMSRuntime");
			if (!(jmsRuntime instanceof ObjectName)) {
				continue;
			}
			Object jmsServers = mbs.getAttribute((ObjectName) jmsRuntime, "JMSServers");
			if (!(jmsServers instanceof ObjectName[])) {
				continue;
			}
			for (ObjectName jmsServer : (ObjectName[]) jmsServers) {
				Object destinations = mbs.getAttribute(jmsServer, "Destinations");
				if (!(destinations instanceof ObjectName[])) {
					continue;
				}
				for (ObjectName destination : (ObjectName[]) destinations) {
					Object name = mbs.getAttribute(destination, "Name");
					if (name != null && String.valueOf(name).contains(destinationName)) {
						found.add(destination);
					}
				}
			}
		}
		return found;
	}

	private static final class Ctx extends InitialContext implements AutoCloseable {
		Ctx() throws javax.naming.NamingException {
			super();
		}
	}
}
