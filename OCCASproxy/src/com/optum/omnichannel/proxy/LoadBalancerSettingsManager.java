package com.optum.omnichannel.proxy;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.SettingsManager;

/**
 * This class extends the SettingsManager class so that the initialize method
 * can be invoked to perform custom configurations.
 * 
 * @author Jeff McDonald
 *
 */
public class LoadBalancerSettingsManager extends SettingsManager<ProxyConfig> {

	public LoadBalancerSettingsManager(SipServletContextEvent event) {
		super(event, ProxyConfig.class);
	}

	@Override
	public void initialize(ProxyConfig config) throws ServletParseException {
		config.initialize();
	}

}
