package org.vorpal.blade.applications.console.config;

import java.util.Set;
import java.util.TreeSet;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;

/**
 * Discovers deployed BLADE applications by querying JMX MBeans.
 */
public class AppDiscovery {

	public static Set<String> queryApps() {
		Set<String> apps = new TreeSet<>();

		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName pattern = new ObjectName("vorpal.blade:Name=*,Type=Configuration,*");
			Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(pattern, null);

			for (ObjectInstance mbean : mbeans) {
				apps.add(mbean.getObjectName().getKeyProperty("Name"));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return apps;
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException {
			super();
		}
	}
}
