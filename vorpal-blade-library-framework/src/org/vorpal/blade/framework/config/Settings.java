/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package org.vorpal.blade.framework.config;

import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * @author Jeff McDonald
 *
 */
public class Settings implements SettingsMXBean {
	private SettingsManager<?> settingsManager;

	public Settings(SettingsManager<?> settingsManager) {
		this.settingsManager = settingsManager;
	}

	@Override
	public String getJSchema() {
		return settingsManager.getJSchema();
	}

	@Override
	public String getDomainJson() {
		try {
			return settingsManager.getDomainJson();
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public void setDomainJson(String json) {
		try {

			System.out.println("setDomainJson begin...");

			settingsManager.setDomainJson(json);
			settingsManager.mergeCurrentFromJson();

			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null) {
				if (settingsManager.getCurrent() instanceof Configuration) {
					Configuration config = (Configuration) settingsManager.getCurrent();

					if (config.getLogging() != null && config.getLogging().resolveLoggingLevel() != null) {
						sipLogger.setLevel(config.getLogging().resolveLoggingLevel());
						sipLogger.log(config.getLogging().resolveLoggingLevel(),
								"Setting logging level to: " + config.getLogging().resolveLoggingLevel());
					}

				}
				settingsManager.logCurrent();
			}else {
				System.out.println("Settings.setDomainJson could not get sipLogger.");
			}

		} catch (Exception e) {
			System.out.println("setDomainJson exception...");

			e.printStackTrace();
		}

		System.out.println("setDomainJson end...");

	}

	@Override
	public String getClusterJson() {
		try {
			return settingsManager.getClusterJson();
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public void setClusterJson(String json) {
		try {
			settingsManager.setClusterJson(json);
			settingsManager.mergeCurrentFromJson();

			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null) {
				if (settingsManager.getCurrent() instanceof Configuration) {
					Configuration config = (Configuration) settingsManager.getCurrent();
					if (config.getLogging() != null && config.getLogging().resolveLoggingLevel() != null) {
						sipLogger.setLevel(config.getLogging().resolveLoggingLevel());
						sipLogger.log(config.getLogging().resolveLoggingLevel(),
								"Setting logging level to: " + config.getLogging().resolveLoggingLevel());
					}
				}
				settingsManager.logCurrent();
			}else {
				System.out.println("Settings.setClusterJson could not get sipLogger.");
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public String getServerJson() {
		try {
			return settingsManager.getServerJson();
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public void setServerJson(String json) {
		try {
			settingsManager.setServerJson(json);
			settingsManager.mergeCurrentFromJson();

			Logger sipLogger = SettingsManager.getSipLogger();
			if (sipLogger != null) {
				if (settingsManager.getCurrent() instanceof Configuration) {
					Configuration config = (Configuration) settingsManager.getCurrent();
					if (config.getLogging() != null && config.getLogging().resolveLoggingLevel() != null) {
						sipLogger.setLevel(config.getLogging().resolveLoggingLevel());
						sipLogger.log(config.getLogging().resolveLoggingLevel(),
								"Setting logging level to: " + config.getLogging().resolveLoggingLevel());
					}
				}
				settingsManager.logCurrent();
			}else {
				System.out.println("Settings.setServerJson could not get sipLogger.");
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
