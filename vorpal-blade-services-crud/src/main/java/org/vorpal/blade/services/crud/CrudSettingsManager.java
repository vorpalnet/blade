package org.vorpal.blade.services.crud;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.SettingsManager;

public class CrudSettingsManager extends SettingsManager<CrudConfiguration> {

	public RuleSet rules;

	public CrudSettingsManager(SipServletContextEvent event, Class<CrudConfiguration> clazz, CrudConfiguration sample) {
		super(event, clazz, sample);
	}

	@Override
	public void initialize(CrudConfiguration config) throws ServletParseException {

	}

}
