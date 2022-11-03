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

		this.getSipLogger().warning("Setting logging level to: " + config.getLoggingLevel());
		switch (config.getLoggingLevel()) {
		case OFF:
			this.getSipLogger().setLevel(Level.OFF);
			break;
		case FINEST:
			this.getSipLogger().setLevel(Level.FINEST);
			break;
		case FINER:
			this.getSipLogger().setLevel(Level.FINER);
			break;
		case FINE:
			this.getSipLogger().setLevel(Level.FINE);
			break;
		case INFO:
			this.getSipLogger().setLevel(Level.INFO);
			break;
		case WARNING:
			this.getSipLogger().setLevel(Level.WARNING);
			break;
		case SEVERE:
			this.getSipLogger().setLevel(Level.SEVERE);
			break;
		case ALL:
			this.getSipLogger().setLevel(Level.ALL);
			break;
		}

	}

}
