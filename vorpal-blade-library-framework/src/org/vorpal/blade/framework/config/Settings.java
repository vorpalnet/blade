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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * @author Jeff McDonald
 *
 */
public class Settings<T> implements SettingsMXBean {
	private Logger sipLogger;
	private Class<T> clazz;
	private T config;
	private T sampleConfig;

	private ObjectMapper objectMapper;
	private SettingsManager<T> settingsManager;

//	private String configName;
	private Path domain;
	private Path cluster;
	private Path server;
//	private Path sample;
//	private Path schema;

	private BufferedWriter bufferedWriter;

	public Settings(Class<T> clazz, SettingsManager<T> settingsManager, String configName, ObjectMapper objectMapper,
			T sampleConfig) {

		this.sampleConfig = sampleConfig;

		this.sipLogger = SettingsManager.getSipLogger();

		this.settingsManager = settingsManager;

		this.clazz = clazz;
//		this.configName = configName;
		this.objectMapper = objectMapper;

		domain = Paths.get("./config/custom/vorpal/" + configName + ".json");
		cluster = Paths.get("./config/custom/vorpal/_clusters" + configName + ".json");
		server = Paths.get("./config/custom/vorpal/_servers" + configName + ".json");
//		sample = Paths.get("./config/custom/vorpal/_samples" + configName + ".json");
//		schema = Paths.get("./config/custom/vorpal/_schemas" + configName + ".jschema");
	}

	public T getConfiguration() throws JsonProcessingException, IOException {
		if (config == null) {
			reload();
		}

		return config;
	}

	@Override
	public void open(String configType) {

		try {
			sipLogger.fine("Opening " + configType.toString() + " configuration file for writing...");

			Path path = null;

			switch (configType) {
			case "DOMAIN":
				path = domain;
				break;
			case "CLUSTER":
				path = cluster;
				break;
			case "SERVER":
				path = server;
				break;
			}

			bufferedWriter = Files.newBufferedWriter(path, StandardOpenOption.TRUNCATE_EXISTING);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void close() {
		try {
			sipLogger.fine("Closing configuration file...");
			bufferedWriter.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void write(String line) {
		try {
			bufferedWriter.write(line);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void reload() {
		sipLogger.info("reloading configuration files...");
		boolean useSampleConfig = true;

		try {

			JsonNode jsonNode = NullNode.getInstance();

			File domainFile = domain.toFile();
			if (domainFile.exists()) {
				useSampleConfig = false;
				jsonNode = objectMapper.readTree(domainFile);
			}

			File clusterFile = cluster.toFile();
			if (clusterFile.exists()) {
				useSampleConfig = false;
				jsonNode = objectMapper.readerForUpdating(jsonNode).readValue(objectMapper.readTree(clusterFile));
			}

			File serverFile = server.toFile();
			if (serverFile.exists()) {
				useSampleConfig = false;
				jsonNode = objectMapper.readerForUpdating(jsonNode).readValue(objectMapper.readTree(serverFile));
			}

			if (useSampleConfig) {

				if (this.sampleConfig != null) {
					sipLogger.warning("Using sample configuration.");
					config = this.sampleConfig;
				} else {
					sipLogger.warning("Using default configuration.");
					config = clazz.getDeclaredConstructor().newInstance();
				}

			} else {
				config = (T) objectMapper.convertValue(jsonNode, clazz);
			}

			if (config instanceof Configuration) {
				Configuration cfg = (Configuration) config;

				if (cfg.getLogging() != null //
						&& cfg.getLogging().resolveUseParentLogging() == false //
						&& cfg.getLogging().resolveLoggingLevel() != null) {
					AsyncSipServlet.getSipLogger().setLevel(cfg.getLogging().resolveLoggingLevel());
					AsyncSipServlet.getSipLogger()
							.setConfigurationLoggingLevel(cfg.getLogging().resolveConfigurationLoggingLevel());
					AsyncSipServlet.getSipLogger()
							.setSequenceDiagramLoggingLevel(cfg.getLogging().resolveSequenceDiagramLoggingLevel());
				}

				if (cfg.getSession() != null) {
					Callflow.setSessionParameters(cfg.getSession());
					AsyncSipServlet.setSessionParameters(cfg.getSession());
				}

			}

			settingsManager.initialize(config);

			sipLogger.logConfiguration(config);

		} catch (Exception e) {
			sipLogger.severe(e);
		}

	}

}
