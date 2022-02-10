package org.vorpal.blade.applications.console.config;

import java.security.PrivilegedAction;

import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public class UpdateAction implements PrivilegedAction {
	MBeanServer server;
	ObjectName name;
	Attribute attribute;

	UpdateAction(MBeanServer server, ObjectName name, Attribute attribute) {
		System.out.println("UpdateAction constructor...");

		this.server = server;
		this.name = name;
		this.attribute = attribute;
	}

	@Override
	public Object run() {
		System.out.println("UpdateAction run...");

		try {
			server.setAttribute(name, attribute);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

}
