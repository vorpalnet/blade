package org.vorpal.blade.applications.events.jms;

import java.util.ArrayList;
import java.util.List;

import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;

/// One WebLogic edit session, wrapping N configuration changes.
///
/// Generalizes what `WlsResourceProvisioner.provisionJms()` does for the
/// analytics stack specifically: `startEdit`, apply, `save`, `activate`, and on
/// any failure `undoUnactivatedChanges` + `stopEdit` so a partial failure never
/// leaves the lock held. The helpers it carries — create-if-absent, find-by-name,
/// set-targets — were already generic there; only the eight hardcoded
/// `BladeAnalytics*` constants were not, and they do not come along.
///
/// **One session per request, never across requests.** A held edit lock blocks
/// WLST and the WebLogic console domain-wide. That discipline is why this is
/// `AutoCloseable` and why nothing hands a session out.
///
/// **Attribute types are read, not guessed.** `WlsResourceProvisioner:102`
/// documents one instance of the int-vs-long trap ("must be Integer, not Long").
/// The JMS attribute surface has dozens — quotas are `long`, redelivery limits
/// are `int`, thresholds are `long` — so [#setAttribute] reads the declared type
/// from `MBeanAttributeInfo` and coerces rather than relying on a hand-kept
/// table that would produce `InvalidAttributeValueException` in the field.
public class JmsEditSession implements AutoCloseable {

	private static final String EDIT = "java:comp/env/jmx/edit";
	private static final String CONFIG_MANAGER =
			"com.bea:Name=ConfigurationManager,Type=weblogic.management.mbeanservers.edit.ConfigurationManagerMBean";
	private static final String EDIT_SERVICE =
			"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean";

	private final InitialContext ctx;
	private final MBeanServer mbs;
	private final ObjectName configManager;
	private final ObjectName domain;
	private final List<String> log = new ArrayList<>();
	private boolean committed;

	public JmsEditSession() throws Exception {
		ctx = new InitialContext();
		mbs = (MBeanServer) ctx.lookup(EDIT);
		configManager = new ObjectName(CONFIG_MANAGER);
		mbs.invoke(configManager, "startEdit", new Object[] { 0, 120000 }, new String[] { "int", "int" });
		ObjectName editService = new ObjectName(EDIT_SERVICE);
		domain = (ObjectName) mbs.getAttribute(editService, "DomainConfiguration");
	}

	public MBeanServer server() {
		return mbs;
	}

	public ObjectName domain() {
		return domain;
	}

	/// A human-readable account of what happened, for the console to show
	/// instead of a bare success.
	public List<String> log() {
		return log;
	}

	public void note(String message) {
		log.add(message);
	}

	/// Save and activate. Anything not committed is rolled back by [#close].
	public void commit() throws Exception {
		mbs.invoke(configManager, "save", null, null);
		mbs.invoke(configManager, "activate", new Object[] { 120000L }, new String[] { "long" });
		committed = true;
		log.add("Changes saved and activated.");
	}

	@Override
	public void close() {
		if (!committed) {
			try {
				mbs.invoke(configManager, "undoUnactivatedChanges", null, null);
			} catch (Exception ignore) {
				// Best effort; the stopEdit below is what releases the lock.
			}
			try {
				mbs.invoke(configManager, "stopEdit", null, null);
			} catch (Exception ignore) {
				// Nothing further to do — a stuck lock is cleared with
				// undoUnactivatedChanges from WLST.
			}
		}
		try {
			ctx.close();
		} catch (Exception ignore) {
			// Nothing useful to do.
		}
	}

	// ------------------------------------------------------------- primitives

	/// Find a child of a collection attribute by its `Name`.
	public ObjectName findByName(ObjectName parent, String collection, String name) throws Exception {
		Object array = mbs.getAttribute(parent, collection);
		if (array instanceof ObjectName[]) {
			for (ObjectName child : (ObjectName[]) array) {
				if (name.equals(mbs.getAttribute(child, "Name"))) {
					return child;
				}
			}
		}
		return null;
	}

	/// Create a child only if it is absent, recording which happened.
	public ObjectName createIfAbsent(ObjectName parent, String collection, String createOp, String name, String label)
			throws Exception {
		ObjectName existing = findByName(parent, collection, name);
		if (existing != null) {
			log.add("• " + label + " '" + name + "' already exists — left alone");
			return existing;
		}
		ObjectName created = (ObjectName) mbs.invoke(parent, createOp, new Object[] { name },
				new String[] { "java.lang.String" });
		log.add("✓ created " + label + " '" + name + "'");
		return created;
	}

	/// Destroy a child. The operation signature is the child's own bean
	/// interface, which is read from `MBeanInfo` rather than assumed — the
	/// descriptor interface name is not something to guess at.
	public void destroy(ObjectName parent, String destroyOp, ObjectName child, String label) throws Exception {
		String signature = signatureOf(parent, destroyOp);
		mbs.invoke(parent, destroyOp, new Object[] { child }, new String[] { signature });
		log.add("✓ destroyed " + label);
	}

	private String signatureOf(ObjectName parent, String operation) throws Exception {
		for (javax.management.MBeanOperationInfo info : mbs.getMBeanInfo(parent).getOperations()) {
			if (info.getName().equals(operation) && info.getSignature().length == 1) {
				return info.getSignature()[0].getType();
			}
		}
		throw new IllegalStateException("no single-argument operation named " + operation + " on " + parent);
	}

	public void setTargets(ObjectName bean, ObjectName... targets) throws Exception {
		mbs.setAttribute(bean, new Attribute("Targets", targets));
	}

	/// Set an attribute, coercing the value to the type the MBean declares.
	///
	/// This is the guard against the int-vs-long class of bug: a JSON number
	/// arrives as an `Integer` or a `Long` depending on its magnitude, and
	/// WebLogic rejects the wrong one.
	public void setAttribute(ObjectName bean, String attribute, Object value) throws Exception {
		String type = null;
		for (MBeanAttributeInfo info : mbs.getMBeanInfo(bean).getAttributes()) {
			if (info.getName().equals(attribute)) {
				type = info.getType();
				break;
			}
		}
		mbs.setAttribute(bean, new Attribute(attribute, coerce(value, type)));
	}

	private static Object coerce(Object value, String type) {
		if (value == null || type == null) {
			return value;
		}
		if (value instanceof Number) {
			Number number = (Number) value;
			switch (type) {
			case "int":
			case "java.lang.Integer":
				return Integer.valueOf(number.intValue());
			case "long":
			case "java.lang.Long":
				return Long.valueOf(number.longValue());
			case "double":
			case "java.lang.Double":
				return Double.valueOf(number.doubleValue());
			default:
				return value;
			}
		}
		if (value instanceof String) {
			String s = (String) value;
			switch (type) {
			case "int":
			case "java.lang.Integer":
				return Integer.valueOf(s);
			case "long":
			case "java.lang.Long":
				return Long.valueOf(s);
			case "boolean":
			case "java.lang.Boolean":
				return Boolean.valueOf(s);
			default:
				return value;
			}
		}
		return value;
	}

	/// Resolve target names to cluster or server MBeans.
	///
	/// Replaces `resolveTargetCluster`, which refused outright when a domain had
	/// more than one cluster — so that code has never run against one. The
	/// console offers a picker instead; falling back to the sole cluster only
	/// when nothing was chosen preserves the old single-cluster behaviour.
	public ObjectName[] resolveTargets(List<String> names) throws Exception {
		ObjectName[] clusters = (ObjectName[]) mbs.getAttribute(domain, "Clusters");
		ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domain, "Servers");

		if (names == null || names.isEmpty()) {
			if (clusters != null && clusters.length == 1) {
				return new ObjectName[] { clusters[0] };
			}
			throw new IllegalStateException("This domain has " + (clusters == null ? 0 : clusters.length)
					+ " clusters; choose a target explicitly.");
		}

		List<ObjectName> resolved = new ArrayList<>();
		for (String name : names) {
			ObjectName match = firstNamed(clusters, name);
			if (match == null) {
				match = firstNamed(servers, name);
			}
			if (match == null) {
				throw new IllegalStateException("No cluster or server named '" + name + "' in this domain.");
			}
			resolved.add(match);
		}
		return resolved.toArray(new ObjectName[0]);
	}

	private ObjectName firstNamed(ObjectName[] candidates, String name) throws Exception {
		if (candidates == null) {
			return null;
		}
		for (ObjectName candidate : candidates) {
			if (name.equals(mbs.getAttribute(candidate, "Name"))) {
				return candidate;
			}
		}
		return null;
	}
}
