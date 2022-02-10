package org.vorpal.blade.applications.console.config;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.util.Hashtable;
import java.util.Set;

import javax.management.Attribute;
import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.vorpal.blade.framework.config.SettingsMXBean;

public class Configurator {
	private ObjectName objectName;
	private String data;
	private String schema;

	public Configurator(String appName) throws MalformedObjectNameException, NamingException {
		System.out.println("Configurator constructor...");
		this.objectName = new ObjectName("vorpal.blade:Name=" + appName + ",Type=Configuration");

		try {
			InitialContext ctx = new InitialContext();
			MBeanServer server = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			Set<ObjectInstance> mbeans = server.queryMBeans(objectName, null);
			ObjectName mbean = ((ObjectInstance) mbeans.toArray()[0]).getObjectName();
			this.data = new String((String) server.getAttribute(mbean, "Data"));
			this.schema = new String((String) server.getAttribute(mbean, "Schema"));
			ctx.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public String loadData() {
		return data;
	}

	public String loadSchema() {
		return schema;
	}

	public void saveData(String json) {
		System.out.println("Configurator saveData...");

		try {
			Hashtable env = new Hashtable();

//			env.put(Context.SECURITY_PRINCIPAL, "weblogic");
//			env.put(Context.SECURITY_CREDENTIALS, "!Gigan1972");

			InitialContext ctx = new InitialContext(env);

//			InitialContext ctx = new InitialContext();
			MBeanServer server = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			Set<ObjectInstance> mbeans = server.queryMBeans(objectName, null);

			UpdateAction action;
			for (ObjectInstance mbean : mbeans) {
				ObjectName name = mbean.getObjectName();

				action = new UpdateAction(server, name, new Attribute("Data", json));
				System.out.println("UpdateAction: " + action);

				AccessControlContext acc = AccessController.getContext();
				System.out.println("AccessControlContext: " + acc);

				SettingsMXBean settings = JMX.newMXBeanProxy(server, name, SettingsMXBean.class);

			}

			ctx.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
