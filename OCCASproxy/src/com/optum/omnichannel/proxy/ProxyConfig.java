package com.optum.omnichannel.proxy;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;

import org.vorpal.blade.framework.proxy.ProxyTier;
import org.vorpal.blade.framework.proxy.ProxyTier.Mode;

import com.optum.omnichannel.proxy.AccessControl.Permission;

import inet.ipaddr.IPAddressString;

public class ProxyConfig implements Serializable {

	private AccessControl.Permission defaultPermission = AccessControl.Permission.deny;
	private List<AccessControl> acl = new LinkedList<>();
	private List<ProxyRule> proxyRules = new LinkedList<>();

	public ProxyConfig() throws ServletParseException {

		SipFactory sipFactory = LoadBalancerServlet.getSipFactory();

		ProxyRule rule1 = new ProxyRule();
		rule1.setId("ruleset-001");

		ProxyTier proxyTier1 = new ProxyTier();
		proxyTier1.setMode(Mode.parallel);
		proxyTier1.setTimeout(5);
		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:172.16.1.1;status=403"));
		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:172.16.1.2;status=404"));
		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:172.16.1.3"));
		proxyTier1.getEndpoints().add(sipFactory.createURI("sip:172.16.1.4"));
		rule1.getTiers().add(proxyTier1);

		ProxyTier proxyTier2 = new ProxyTier();
		proxyTier2.setMode(Mode.serial);
		proxyTier2.setTimeout(5);
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:172.16.2.1;status=410"));
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:172.16.2.2;status=503"));
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:172.16.2.3"));
		proxyTier2.getEndpoints().add(sipFactory.createURI("sip:172.16.2.4"));
		rule1.getTiers().add(proxyTier2);

		proxyRules.add(rule1);

		AccessControl ac1 = new AccessControl();
		ac1.setSource("192.168.1.0/24");
//		ac1.setSource("192.168.1.116");
		ac1.setPermission(Permission.allow);
		ac1.setProxyRuleId("ruleset-001");
		acl.add(ac1);

		AccessControl ac2 = new AccessControl();
		ac2.setSource("192.168.64.1");
		ac2.setPermission(Permission.allow);
		ac2.setProxyRuleId("ruleset-001");
		acl.add(ac2);

		AccessControl ac3 = new AccessControl();
		ac3.setSource("10.0.1.0/24");
		ac3.setPermission(Permission.deny);
		acl.add(ac3);

	}

	public List<AccessControl> getAcl() {
		return acl;
	}

	public void setAcl(List<AccessControl> acl) {
		this.acl = acl;
	}

	public List<ProxyRule> getProxyRules() {
		return proxyRules;
	}

	public void setProxyRules(List<ProxyRule> proxyRules) {
		this.proxyRules = proxyRules;
	}

	public void initialize() throws ServletParseException {

		LoadBalancerServlet.aclConfig = new AclConfig(this);

	}

	public AccessControl.Permission getDefaultPermission() {
		return defaultPermission;
	}

	public void setDefaultPermission(AccessControl.Permission defaultPermission) {
		this.defaultPermission = defaultPermission;
	}

}
