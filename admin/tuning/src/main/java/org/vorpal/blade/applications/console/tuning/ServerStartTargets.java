package org.vorpal.blade.applications.console.tuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/// The ServerStart owners in config.xml: the things a JVM profile can be applied to.
///
/// In MBean-mode start (`weblogic.StartScriptEnabled=false`) Node Manager builds a server's
/// java line from its `ServerStart` MBean alone, and config.xml holds exactly one such MBean
/// per **static** server (the AdminServer, engine0) and one per **server template**. A dynamic
/// engine (engine1..N) has no ServerStart of its own: it is materialized from its cluster's
/// template at boot, and `ConfigurationMBean.isDynamicallyCreated()` is how the domain tree
/// marks it. Listing `Domain.Servers` and writing `Type=Server` for every name, as this app
/// once did, put dynamic engines in the assignment table where a write could never reach the
/// java line, and left the template, the one MBean that governs them, untouchable.
///
/// So a target is either a static server or a template, never a dynamic server. The walk runs
/// on whichever MBeanServer the caller hands in: the DomainRuntime tree for reads, the Edit
/// tree inside an edit session for writes. The `ObjectName`s come from the tree itself rather
/// than being assembled from a name, so the two trees never disagree about a type key.
final class ServerStartTargets {

	/// Which config.xml element owns the ServerStart.
	enum Kind {
		/// `<server>`: the AdminServer or a static engine (engine0).
		SERVER,
		/// `<server-template>`: governs every dynamic engine in its cluster.
		TEMPLATE
	}

	/// One ServerStart owner and the four attributes Node Manager reads from it.
	static final class Target {
		final String name;
		final Kind kind;
		/// Cluster name, or "" for an unclustered server.
		final String cluster;
		/// Machine name, or "" when unassigned (NM then starts it wherever the AdminServer is).
		final String machine;
		/// For a template, the dynamic servers it produces; empty for a static server.
		final List<String> members;
		final String classPath;
		final String arguments;
		final String javaHome;
		final String javaVendor;
		/// The owning Server/ServerTemplate MBean in the tree it was read from.
		final ObjectName owner;
		/// The ServerStart child, or null when the tree has none for this owner.
		final ObjectName serverStart;

		Target(String name, Kind kind, String cluster, String machine, List<String> members, String classPath,
				String arguments, String javaHome, String javaVendor, ObjectName owner, ObjectName serverStart) {
			this.name = name;
			this.kind = kind;
			this.cluster = cluster;
			this.machine = machine;
			this.members = members;
			this.classPath = classPath;
			this.arguments = arguments;
			this.javaHome = javaHome;
			this.javaVendor = javaVendor;
			this.owner = owner;
			this.serverStart = serverStart;
		}

		String kindName() {
			return kind == Kind.TEMPLATE ? "template" : "server";
		}
	}

	private static final String DOMAIN_RUNTIME_SERVICE =
			"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean";
	private static final String EDIT_SERVICE =
			"com.bea:Name=EditService,Type=weblogic.management.mbeanservers.edit.EditServiceMBean";

	private ServerStartTargets() {
	}

	/// The domain's configuration root on the DomainRuntime MBeanServer. Goes through the
	/// service MBean: a direct `Name=DomainConfiguration,Type=Domain` lookup throws on WLS 14.1.1.
	static ObjectName runtimeDomainConfig(MBeanServer domainRuntime) throws Exception {
		return (ObjectName) domainRuntime.getAttribute(new ObjectName(DOMAIN_RUNTIME_SERVICE), "DomainConfiguration");
	}

	/// The domain's configuration root on the Edit MBeanServer, valid inside an edit session.
	static ObjectName editDomainConfig(MBeanServer edit) throws Exception {
		return (ObjectName) edit.getAttribute(new ObjectName(EDIT_SERVICE), "DomainConfiguration");
	}

	/// The AdminServer's name, from the domain root; "AdminServer" if the attribute is unreadable.
	static String adminServerName(MBeanServer mbs, ObjectName domainConfig) {
		try {
			Object n = mbs.getAttribute(domainConfig, "AdminServerName");
			if (n != null && !n.toString().isEmpty()) return n.toString();
		} catch (Exception ignore) {
		}
		return "AdminServer";
	}

	/// Every ServerStart owner in the domain: static servers first (in tree order), then
	/// templates. Dynamic servers are skipped. A template's `members` and `cluster` come from
	/// the cluster whose `DynamicServers` names it; a template no cluster uses has neither.
	static List<Target> list(MBeanServer mbs, ObjectName domainConfig) throws Exception {
		List<Target> out = new ArrayList<>();

		ObjectName[] servers = (ObjectName[]) mbs.getAttribute(domainConfig, "Servers");
		if (servers != null) {
			for (ObjectName server : servers) {
				if (isDynamicallyCreated(mbs, server)) continue;
				out.add(read(mbs, server, Kind.SERVER, Collections.emptyList()));
			}
		}

		// Template -> the dynamic server names it produces, via each cluster's DynamicServers.
		Map<String, List<String>> membersByTemplate = new LinkedHashMap<>();
		ObjectName[] clusters = (ObjectName[]) mbs.getAttribute(domainConfig, "Clusters");
		if (clusters != null) {
			for (ObjectName cluster : clusters) {
				try {
					ObjectName dyn = (ObjectName) mbs.getAttribute(cluster, "DynamicServers");
					if (dyn == null) continue;
					ObjectName template = (ObjectName) mbs.getAttribute(dyn, "ServerTemplate");
					if (template == null) continue;
					String[] names = (String[]) mbs.getAttribute(dyn, "DynamicServerNames");
					List<String> members = new ArrayList<>();
					if (names != null) Collections.addAll(members, names);
					membersByTemplate.put(nameOf(mbs, template), members);
				} catch (Exception ignore) {
					// A cluster without a dynamic-servers block is a static cluster: nothing to map.
				}
			}
		}

		ObjectName[] templates = (ObjectName[]) mbs.getAttribute(domainConfig, "ServerTemplates");
		if (templates != null) {
			for (ObjectName template : templates) {
				List<String> members = membersByTemplate.get(nameOf(mbs, template));
				out.add(read(mbs, template, Kind.TEMPLATE, members == null ? Collections.emptyList() : members));
			}
		}
		return out;
	}

	/// The target with this name, or null.
	static Target find(List<Target> targets, String name) {
		for (Target t : targets) {
			if (t.name.equals(name)) return t;
		}
		return null;
	}

	/// Write ClassPath and/or Arguments on a target read from the Edit tree. A null value
	/// leaves that attribute alone. Nothing here starts or activates the edit session.
	static void write(MBeanServer edit, Target target, String classPath, String arguments) throws Exception {
		if (target.serverStart == null) {
			throw new IllegalStateException(target.name + " has no ServerStart MBean");
		}
		if (classPath != null) {
			edit.setAttribute(target.serverStart, new Attribute("ClassPath", classPath));
		}
		if (arguments != null) {
			edit.setAttribute(target.serverStart, new Attribute("Arguments", arguments));
		}
	}

	private static Target read(MBeanServer mbs, ObjectName owner, Kind kind, List<String> members) throws Exception {
		String name = nameOf(mbs, owner);
		String cluster = refName(mbs, owner, "Cluster");
		String machine = refName(mbs, owner, "Machine");
		ObjectName serverStart = (ObjectName) mbs.getAttribute(owner, "ServerStart");
		String classPath = "", arguments = "", javaHome = "", javaVendor = "";
		if (serverStart != null) {
			classPath = str(mbs, serverStart, "ClassPath");
			arguments = str(mbs, serverStart, "Arguments");
			javaHome = str(mbs, serverStart, "JavaHome");
			javaVendor = str(mbs, serverStart, "JavaVendor");
		}
		return new Target(name, kind, cluster, machine, members, classPath, arguments, javaHome, javaVendor, owner,
				serverStart);
	}

	/// `ConfigurationMBean.isDynamicallyCreated()`, surfaced as the `DynamicallyCreated`
	/// attribute. Unreadable is treated as static: a false negative costs a row in the table,
	/// a false positive would hide a real server.
	private static boolean isDynamicallyCreated(MBeanServer mbs, ObjectName bean) {
		try {
			Object v = mbs.getAttribute(bean, "DynamicallyCreated");
			return v instanceof Boolean && (Boolean) v;
		} catch (Exception e) {
			return false;
		}
	}

	private static String nameOf(MBeanServer mbs, ObjectName bean) throws Exception {
		return String.valueOf(mbs.getAttribute(bean, "Name"));
	}

	private static String refName(MBeanServer mbs, ObjectName bean, String attribute) {
		try {
			ObjectName ref = (ObjectName) mbs.getAttribute(bean, attribute);
			return ref == null ? "" : nameOf(mbs, ref);
		} catch (Exception e) {
			return "";
		}
	}

	private static String str(MBeanServer mbs, ObjectName bean, String attribute) {
		try {
			Object v = mbs.getAttribute(bean, attribute);
			return v == null ? "" : v.toString();
		} catch (Exception e) {
			return "";
		}
	}
}
