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
package org.vorpal.blade.framework.v2.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.Logger;

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

	private Path domain;
	private Path cluster;
	private Path server;
	private Path sample;
	private Path schema;

	private BufferedWriter bufferedWriter;
	private BufferedReader bufferedReader;

	public Settings(Class<T> clazz, SettingsManager<T> settingsManager, String configName, ObjectMapper objectMapper,
			T sampleConfig) {

		this.sampleConfig = sampleConfig;

		this.sipLogger = SettingsManager.getSipLogger();

		this.settingsManager = settingsManager;

		this.clazz = clazz;
		this.objectMapper = objectMapper;

		domain = Paths.get("./config/custom/vorpal/" + configName + ".json");
		cluster = Paths.get("./config/custom/vorpal/_clusters/" + configName + ".json");
		server = Paths.get("./config/custom/vorpal/_servers/" + configName + ".json");
		sample = Paths.get("./config/custom/vorpal/_samples/" + configName + ".json.SAMPLE");
		schema = Paths.get("./config/custom/vorpal/_schemas/" + configName + ".jschema");
	}

	public T getConfiguration() throws JsonProcessingException, IOException {
		if (config == null) {
			reload();
		}

		return config;
	}

	@Override
	public long getLastModified(String configType) {

		long timestamp = 0;

		try {
//			sipLogger.fine("Checking LastModified " + configType.toString() + " configuration...");

			Path path = getPath(configType);

			if (path.toFile().exists()) {
				BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
				timestamp = attr.lastModifiedTime().toMillis();
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		sipLogger.info("getLastModified: " + timestamp);

		return timestamp;
	}

	@Override
	public void openForWrite(String configType) {

		try {
			sipLogger.fine("Opening " + configType + " configuration file for writing...");
			Path path = getPath(configType);
			bufferedWriter = Files.newBufferedWriter(path, //
					StandardOpenOption.CREATE, //
					StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void openForRead(String configType) {

		try {
			Path path = getPath(configType);
			sipLogger.fine("Opening " + path.getFileName() + " for reading...");

			if (path.toFile().exists()) {
				bufferedWriter = Files.newBufferedWriter(path, StandardOpenOption.TRUNCATE_EXISTING);
				bufferedReader = Files.newBufferedReader(path);
			} else {
				sipLogger.fine(path.getFileName() + " does not exist.");
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void close() {
		try {
			sipLogger.fine("Closing configuration file.");

			if (bufferedReader != null) {
				bufferedReader.close();
			}

			if (bufferedWriter != null) {
				bufferedWriter.close();
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void write(String line) {
		try {

			if (bufferedWriter != null) {
				bufferedWriter.write(line);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public String read() {
		String line = null;

		try {

			if (bufferedReader != null) {
				line = bufferedReader.readLine();
			}

		} catch (IOException e) {
			sipLogger.severe(e);
		}

		return line;
	}

	@Override
	public void reload() {
		sipLogger.info("Reloading configuration file.");
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

			settingsManager.current = (T) this.getConfiguration();

			settingsManager.initialize(config);

			sipLogger.logConfiguration(config);

		} catch (Exception e) {
			sipLogger.severe(e);
		}

	}

	public Path getPath(String configType) {
		Path path = null;

		switch (configType) {
		case "DOMAIN":
		case "Domain":
		case "domain":
			path = domain;
			break;
		case "CLUSTER":
		case "Cluster":
		case "cluster":
			path = cluster;
			break;

		case "SERVER":
		case "Server":
		case "server":
			path = server;
			break;

		case "SCHEMA":
		case "Schema":
		case "schema":
			path = schema;
			break;

		case "SAMPLE":
		case "Sample":
		case "sample":
			path = sample;
			break;

		}

		return path;
	}

}
