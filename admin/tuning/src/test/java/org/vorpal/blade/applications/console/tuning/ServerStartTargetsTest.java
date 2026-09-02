package org.vorpal.blade.applications.console.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

import org.vorpal.blade.applications.console.tuning.ServerStartTargets.Kind;
import org.vorpal.blade.applications.console.tuning.ServerStartTargets.Target;

/// The targets are the ServerStart owners: static servers and templates. A dynamic engine is
/// not one, however many of them the domain tree lists.
public class ServerStartTargetsTest {

	/// A config MBean stand-in: a bag of attributes.
	private static final class Bean implements DynamicMBean {
		final Map<String, Object> attrs = new LinkedHashMap<>();

		Bean with(String name, Object value) {
			attrs.put(name, value);
			return this;
		}

		@Override
		public Object getAttribute(String attribute) throws javax.management.AttributeNotFoundException {
			if (!attrs.containsKey(attribute)) throw new javax.management.AttributeNotFoundException(attribute);
			return attrs.get(attribute);
		}

		@Override
		public void setAttribute(Attribute attribute) {
			attrs.put(attribute.getName(), attribute.getValue());
		}

		@Override
		public AttributeList getAttributes(String[] attributes) {
			return new AttributeList();
		}

		@Override
		public AttributeList setAttributes(AttributeList attributes) {
			return new AttributeList();
		}

		@Override
		public Object invoke(String actionName, Object[] params, String[] signature) {
			return null;
		}

		@Override
		public MBeanInfo getMBeanInfo() {
			return new MBeanInfo(Bean.class.getName(), "", null, null, null, null);
		}
	}

	private final MBeanServer mbs = MBeanServerFactory.newMBeanServer();

	private ObjectName register(String name, Bean bean) throws Exception {
		ObjectName on = new ObjectName(name);
		mbs.registerMBean(bean, on);
		return on;
	}

	private ObjectName serverStart(String owner, String cp, String args) throws Exception {
		return register("com.bea:Name=" + owner + ",Type=ServerStart",
				new Bean().with("ClassPath", cp).with("Arguments", args).with("JavaHome", "").with("JavaVendor", ""));
	}

	/// The ashburn shape: AdminServer + static engine0 + a dynamic cluster of two engines.
	private ObjectName ashburn() throws Exception {
		ObjectName cluster = register("com.bea:Name=engines,Type=Cluster", new Bean().with("Name", "engines"));
		ObjectName machine = register("com.bea:Name=admin-box,Type=Machine", new Bean().with("Name", "admin-box"));

		ObjectName admin = register("com.bea:Name=AdminServer,Type=Server", new Bean().with("Name", "AdminServer")
				.with("DynamicallyCreated", false).with("Cluster", null).with("Machine", machine)
				.with("ServerStart", serverStart("AdminServer", "/x/weblogic_sip.jar", "-Xmx1g -Dadmin=1")));
		ObjectName engine0 = register("com.bea:Name=engine0,Type=Server", new Bean().with("Name", "engine0")
				.with("DynamicallyCreated", false).with("Cluster", cluster).with("Machine", machine)
				.with("ServerStart", serverStart("engine0", "/x/weblogic_sip.jar", "-Xmx768m")));
		ObjectName engine1 = register("com.bea:Name=engine1,Type=Server", new Bean().with("Name", "engine1")
				.with("DynamicallyCreated", true).with("Cluster", cluster).with("Machine", null)
				.with("ServerStart", serverStart("engine1", "", "")));
		ObjectName engine2 = register("com.bea:Name=engine2,Type=Server", new Bean().with("Name", "engine2")
				.with("DynamicallyCreated", true).with("Cluster", cluster).with("Machine", null)
				.with("ServerStart", null));

		ObjectName template = register("com.bea:Name=engines-template,Type=ServerTemplate",
				new Bean().with("Name", "engines-template").with("Cluster", cluster).with("Machine", null)
						.with("ServerStart", serverStart("engines-template", "/x/weblogic_sip.jar", "-Xmx1024m")));
		ObjectName dyn = register("com.bea:Name=engines,Type=DynamicServers", new Bean().with("ServerTemplate", template)
				.with("DynamicServerNames", new String[]{"engine1", "engine2"}));
		mbs.setAttribute(cluster, new Attribute("DynamicServers", dyn));

		return register("com.bea:Name=test,Type=Domain", new Bean().with("Name", "test")
				.with("AdminServerName", "AdminServer")
				.with("Servers", new ObjectName[]{admin, engine0, engine1, engine2})
				.with("Clusters", new ObjectName[]{cluster})
				.with("ServerTemplates", new ObjectName[]{template}));
	}

	@Test
	public void staticServersAndTemplatesAreTargetsDynamicEnginesAreNot() throws Exception {
		List<Target> targets = ServerStartTargets.list(mbs, ashburn());

		assertEquals(Arrays.asList("AdminServer", "engine0", "engines-template"),
				Arrays.asList(targets.stream().map(t -> t.name).toArray()));
		assertNull(ServerStartTargets.find(targets, "engine1"));
		assertNull(ServerStartTargets.find(targets, "engine2"));
	}

	@Test
	public void aTemplateKnowsItsClusterAndTheEnginesItProduces() throws Exception {
		List<Target> targets = ServerStartTargets.list(mbs, ashburn());
		Target template = ServerStartTargets.find(targets, "engines-template");

		assertEquals(Kind.TEMPLATE, template.kind);
		assertEquals("template", template.kindName());
		assertEquals("engines", template.cluster);
		assertEquals(Arrays.asList("engine1", "engine2"), template.members);
		assertEquals("-Xmx1024m", template.arguments);
		assertEquals("/x/weblogic_sip.jar", template.classPath);
	}

	@Test
	public void aStaticServerCarriesItsMachineAndServerStart() throws Exception {
		List<Target> targets = ServerStartTargets.list(mbs, ashburn());
		Target engine0 = ServerStartTargets.find(targets, "engine0");

		assertEquals(Kind.SERVER, engine0.kind);
		assertEquals("admin-box", engine0.machine);
		assertEquals("engines", engine0.cluster);
		assertTrue(engine0.members.isEmpty());
		assertEquals("-Xmx768m", engine0.arguments);
		assertEquals("AdminServer", ServerStartTargets.adminServerName(mbs, new ObjectName("com.bea:Name=test,Type=Domain")));
	}

	@Test
	public void writeSetsOnlyWhatItIsGiven() throws Exception {
		List<Target> targets = ServerStartTargets.list(mbs, ashburn());
		Target engine0 = ServerStartTargets.find(targets, "engine0");

		ServerStartTargets.write(mbs, engine0, null, "-Xmx2g");
		assertEquals("-Xmx2g", mbs.getAttribute(engine0.serverStart, "Arguments"));
		assertEquals("/x/weblogic_sip.jar", mbs.getAttribute(engine0.serverStart, "ClassPath"));

		ServerStartTargets.write(mbs, engine0, "/y/weblogic_sip.jar", null);
		assertEquals("/y/weblogic_sip.jar", mbs.getAttribute(engine0.serverStart, "ClassPath"));
		assertEquals("-Xmx2g", mbs.getAttribute(engine0.serverStart, "Arguments"));
	}
}
