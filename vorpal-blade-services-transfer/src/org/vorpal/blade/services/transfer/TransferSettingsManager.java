package org.vorpal.blade.services.transfer;

import java.util.logging.Level;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.services.transfer.TransferSettings.LoggingLevel;

public class TransferSettingsManager extends SettingsManager<TransferSettings> {

	public TransferSettingsManager(SipServletContextEvent event, Class clazz, TransferSettings sample) {
		super(event, clazz, sample);
	}

	@Override
	public void initialize(TransferSettings config) throws ServletParseException {
		sipLogger.info("Initializng configuration logging level to: " + config.getLoggingLevel());

		switch (config.getLoggingLevel()) {
		case OFF:
			SettingsManager.getSipLogger().setLevel(Level.OFF);
			break;
		case FINEST:
			SettingsManager.getSipLogger().setLevel(Level.FINEST);
			break;
		case FINER:
			SettingsManager.getSipLogger().setLevel(Level.FINER);
			break;
		case FINE:
			SettingsManager.getSipLogger().setLevel(Level.FINE);
			break;
		case INFO:
			SettingsManager.getSipLogger().setLevel(Level.INFO);
			break;
		case WARNING:
			SettingsManager.getSipLogger().setLevel(Level.WARNING);
			break;
		case SEVERE:
			SettingsManager.getSipLogger().setLevel(Level.SEVERE);
			break;
		case ALL:
			SettingsManager.getSipLogger().setLevel(Level.ALL);
			break;
		}

	}

}
