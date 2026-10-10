package org.vorpal.blade.framework.v2.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

import org.junit.jupiter.api.Test;

/// An application that never calls [SettingsManager#unregister()] still
/// loses its MBean when its context is destroyed.
class SettingsUnregistrarTest {

	public interface ProbeMBean {
		int getValue();
	}

	/// A servlet context that only keeps attributes.
	private static ServletContext context() {
		Map<String, Object> attributes = new HashMap<>();
		return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
				new Class<?>[] { ServletContext.class }, (proxy, method, args) -> {
					switch (method.getName()) {
					case "getAttribute":
						return attributes.get(args[0]);
					case "setAttribute":
						attributes.put((String) args[0], args[1]);
						return null;
					case "removeAttribute":
						attributes.remove(args[0]);
						return null;
					default:
						return null;
					}
				});
	}

	@Test
	void contextDestroyedUnregistersTrackedManagers() throws Exception {
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		ObjectName name = new ObjectName("vorpal.blade:Name=unregistrar-test,Type=Configuration");
		server.registerMBean(new StandardMBean(() -> 1, ProbeMBean.class), name);

		SettingsManager<Object> manager = new SettingsManager<>();
		manager.server = server;
		manager.objectName = name;

		ServletContext context = context();
		SettingsUnregistrar.track(context, manager);
		new SettingsUnregistrar().contextDestroyed(new ServletContextEvent(context));

		assertFalse(server.isRegistered(name));
		assertNull(context.getAttribute(SettingsUnregistrar.class.getName()));
		manager.unregister(); // an app that also unregisters itself is harmless
	}

	@Test
	void unregisterTwiceIsHarmless() throws Exception {
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		ObjectName name = new ObjectName("vorpal.blade:Name=unregistrar-twice,Type=Configuration");
		server.registerMBean(new StandardMBean(() -> 1, ProbeMBean.class), name);
		SettingsManager<Object> manager = new SettingsManager<>();
		manager.server = server;
		manager.objectName = name;
		assertTrue(server.isRegistered(name));
		manager.unregister();
		manager.unregister();
		assertFalse(server.isRegistered(name));
	}
}
