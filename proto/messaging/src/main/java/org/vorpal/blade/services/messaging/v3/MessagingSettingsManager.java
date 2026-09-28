package org.vorpal.blade.services.messaging.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.v3.configuration.SettingsManager;

/// `messaging.json`. Nothing to redo on a reload: every setting is read where it is used.
public class MessagingSettingsManager extends SettingsManager<MessagingSettings> {

	public MessagingSettingsManager(SipServletContextEvent event) throws ServletException, IOException {
		super(event);
	}

	@Override
	protected MessagingSettings sample() {
		return new MessagingSettingsSample();
	}

	@Override
	protected void refreshed(MessagingSettings config) {
		// read live by Rooms
	}
}
