package org.vorpal.blade.services.crud;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;
import org.vorpal.blade.framework.config.SettingsManager;

public class CrudSettingsManager extends SettingsManager {
	public RuleSet rules;

	public CrudSettingsManager(SipServletContextEvent event, Class clazz, CrudConfiguration sample)
			throws ServletException, IOException {
		super((SipServletContextEvent) event, clazz, (Object) sample);
	}

	public void initialize(CrudConfiguration config) throws ServletParseException {
	}
}
