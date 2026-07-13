package org.vorpal.blade.services.proxy.balancer;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.B2buaServlet;
import org.vorpal.blade.services.proxy.balancer.config.BalancerConfig;
import org.vorpal.blade.services.proxy.balancer.config.BalancerConfigSample;

/// The Proxy Balancer — distributes initial INVITEs across the tiers of a
/// configured [org.vorpal.blade.framework.v2.proxy.ProxyPlan], selected by
/// the request URI's host. [InviteCallflow] forks each tier with the fan-out
/// primitives; the b2bua callflows inherited from [B2buaServlet] relay
/// everything in-dialog. With `session:passthru` set in the config, the
/// balancer stitches the endpoints' Contacts together and drops out of the
/// dialog after setup — a true routing-only touch point.
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyBalancerServlet extends B2buaServlet {

	private static final long serialVersionUID = 1L;
	public static SettingsManager<BalancerConfig> settingsManager;
	public static String servletContextName;

	private ObjectName healthObjectName;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		servletContextName = "sip:" + event.getServletContext().getServletContextName();
		settingsManager = new ProxyBalancerSettingsManager(event, BalancerConfig.class,
				new BalancerConfigSample());

		// per-node health view: OPTIONS ping cycle + JMX publication
		new OptionsPingCallflow().start();
		registerHealthMBean();
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		unregisterHealthMBean();
		settingsManager.unregister();
	}

	/// ObjectName mirrors the Configuration/Source MBeans —
	/// `vorpal.blade:Name=proxy-balancer,Type=EndpointHealth[,Cluster=...]` —
	/// so the admin tier walks engines the same way the Trace viewer does.
	/// Explicit StandardMBean, never reflective introspection.
	private void registerHealthMBean() {
		try {
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			String cluster = clusterName(server);
			ObjectName on = new ObjectName(cluster != null
					? "vorpal.blade:Name=proxy-balancer,Type=EndpointHealth,Cluster=" + cluster
					: "vorpal.blade:Name=proxy-balancer,Type=EndpointHealth");
			StandardMBean mxbean = new StandardMBean(new EndpointHealthMBean(), EndpointHealthMXBean.class, true);
			healthObjectName = server.registerMBean(mxbean, on).getObjectName();
		} catch (Exception e) {
			sipLogger.warning("ProxyBalancerServlet - EndpointHealth MBean registration failed: " + e.getMessage());
		}
	}

	private void unregisterHealthMBean() {
		if (healthObjectName != null) {
			try {
				ManagementFactory.getPlatformMBeanServer().unregisterMBean(healthObjectName);
			} catch (Exception e) {
				// already gone; nothing to do
			} finally {
				healthObjectName = null;
			}
		}
	}

	/// The cluster this node belongs to, or null on a non-clustered server.
	/// Same discovery the SettingsManager and SourceRegistrar do — the
	/// ObjectNames must agree on the Cluster key.
	private static String clusterName(MBeanServer server) {
		try {
			String serverName = System.getProperty("weblogic.Name");
			ObjectName managedServer = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
			ObjectName cluster = (ObjectName) server.getAttribute(managedServer, "Cluster");
			return cluster != null ? (String) server.getAttribute(cluster, "Name") : null;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		if (request.isInitial() && "INVITE".equals(request.getMethod())) {
			return new InviteCallflow();
		}
		// b2bua dispatch: Reinvite / Terminate / Passthru
		return super.chooseCallflow(request);
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

}
