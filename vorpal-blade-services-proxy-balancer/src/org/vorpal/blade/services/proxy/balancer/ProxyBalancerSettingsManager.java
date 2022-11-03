package org.vorpal.blade.services.proxy.balancer;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.SettingsManager;

public class ProxyBalancerSettingsManager extends SettingsManager<ProxyBalancerConfig>{

	public ProxyBalancerSettingsManager(SipServletContextEvent event, Class<ProxyBalancerConfig> clazz) {
		super(event, clazz);
	}

	@Override
	public void initialize(ProxyBalancerConfig config) throws ServletParseException {

		
		
		
		
		
		
	}
	
	
	
	
	

}
