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

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;

import inet.ipaddr.IPAddress;

/**
 * @author Jeff McDonald
 *
 */
public class SettingsManager<T> {
	private T current;
	private ObjectName objectName;
	private MBeanServer server;
	private ObjectMapper mapper;
	private Class<T> clazz;
	private ObjectInstance oi;
	private Settings settings;

	private static String serverName;
	private static String clusterName;

	protected String servletContextName;
	protected Path domainPath;
	protected Path clusterPath;
	protected Path serverPath;
	protected Path schemaPath;

	public static SipFactory sipFactory;
	public static Logger sipLogger;

	private JsonNode domainNode = NullNode.getInstance();
	private JsonNode clusterNode = NullNode.getInstance();
	private JsonNode serverNode = NullNode.getInstance();
	private JsonNode mergedNode = NullNode.getInstance();

	public static SipFactory getSipFactory() {
		return sipFactory;
	}

	public static void setSipFactory(SipFactory sipFactory) {
		SettingsManager.sipFactory = sipFactory;
	}

	public static Logger getSipLogger() {
		return sipLogger;
	}

	public static void setSipLogger(Logger sipLogger) {
		SettingsManager.sipLogger = sipLogger;
	}

	public String getDomainJson() throws JsonProcessingException {
		sipLogger.fine("getDomainlJson...");
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(domainNode);
	}

	public void setDomainJson(String domainJson) throws JsonMappingException, JsonProcessingException {
		sipLogger.fine("setDomainJson...");
		domainNode = mapper.readTree(domainJson);
		sipLogger.fine(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(domainNode));
	}

	public String getServerJson() throws JsonProcessingException {
		sipLogger.fine("getServerJson...");
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(serverNode);
	}

	public void setServerJson(String serverJson) throws JsonMappingException, JsonProcessingException {
		sipLogger.fine("setServerJson...");
		serverNode = mapper.readTree(serverJson);
		sipLogger.fine(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(serverNode));
	}

	public String getClusterJson() throws JsonProcessingException {
		sipLogger.fine("getServerJson...");
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(clusterNode);
	}

	public void setClusterJson(String clusterJson) throws JsonMappingException, JsonProcessingException {
		sipLogger.fine("setClusterJson...");
		clusterNode = mapper.readTree(clusterJson);
		sipLogger.fine(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(clusterNode));

	}

	public SettingsManager(String name, Class<T> clazz, ObjectMapper mapper) {
		this.mapper = mapper;
		this.build(name, clazz, mapper);
	}

	public SettingsManager(String name, Class<T> clazz) {
		this.build(name, clazz, null);
	}

	public SettingsManager(SipServletContextEvent event, Class<T> clazz, ObjectMapper mapper) {
		this.sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		this.mapper = mapper;
		this.build(basename(event.getServletContext().getServletContextName()), clazz, mapper);
	}

	public SettingsManager(SipServletContextEvent event, Class<T> clazz) {
		this.sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		this.build(basename(event.getServletContext().getServletContextName()), clazz, null);
	}

	public void build(String name, Class<T> clazz, ObjectMapper _mapper) {

		this.servletContextName = basename(name);
		this.clazz = clazz;

		try {
			
			if (null==_mapper) {
				this.mapper = new ObjectMapper();
			} else {
				this.mapper = _mapper;
			}
			
			sipLogger = LogManager.getLogger(name);
			settings = new Settings(this);

			// Get the managed server & cluster names
			String configPath = "config/custom/vorpal/";
			domainPath = Paths.get(configPath);
			Files.createDirectories(domainPath);
			schemaPath = Paths.get(configPath + "schemas/");
			Files.createDirectories(schemaPath);
			server = ManagementFactory.getPlatformMBeanServer();
			serverName = System.getProperty("weblogic.Name");
			serverPath = Paths.get(configPath + "server/" + serverName);
			Files.createDirectories(serverPath);
			ObjectName managedServerName = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
			ObjectName clusterObjectName = (ObjectName) server.getAttribute(managedServerName, "Cluster");
			if (clusterObjectName != null) {
				clusterName = (String) server.getAttribute(clusterObjectName, "Name");
				clusterPath = Paths.get(configPath + "cluster/" + clusterName);
				Files.createDirectories(clusterPath);
			}

			// Support for SipFactory classes



			this.mapper.registerModule(new SimpleModule().addDeserializer(URI.class, new JsonUriDeserializer()));
			this.mapper.registerModule(new SimpleModule().addDeserializer(Address.class, new JsonAddressDeserializer()));
			this.mapper.registerModule(new SimpleModule().addDeserializer(IPAddress.class, new JsonIPAddressDeserializer()));
			this.mapper.registerModule(new SimpleModule().addSerializer(URI.class, new JsonUriSerializer()));
			this.mapper.registerModule(new SimpleModule().addSerializer(Address.class, new JsonAddressSerializer()));
			this.mapper.registerModule(new SimpleModule().addSerializer(IPAddress.class, new JsonIPAddressSerializer()));
			this.mapper.registerModule(
					new SimpleModule().addKeyDeserializer(inet.ipaddr.Address.class, new InetAddressKeyDeserializer()));

			// Don't both to save attributes set to null.
			this.mapper.setSerializationInclusion(Include.NON_NULL);

			if (clusterName != null) {
				objectName = new ObjectName(
						"vorpal.blade:Name=" + servletContextName + ",Type=Configuration,Cluster=" + clusterName);
			} else {
				objectName = new ObjectName("vorpal.blade:Name=" + servletContextName + ",Type=Configuration");
			}
			register();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

	}

	/**
	 * This method is intended to be overridden to allow configurations that require
	 * additional work before they are ready to use.
	 * 
	 * @throws ServletParseException
	 */
	public void initialize(T config) throws ServletParseException {

	}

	public void register() throws InstanceAlreadyExistsException, MBeanRegistrationException,
			NotCompliantMBeanException, JsonGenerationException, JsonMappingException, InstantiationException,
			IllegalAccessException, IOException, ServletParseException {
		loadConfigFile(clazz);

		oi = server.registerMBean(this.settings, objectName);
		objectName = oi.getObjectName();

	}

	public void unregister() throws MBeanRegistrationException, InstanceNotFoundException {
		server.unregisterMBean(objectName);
	}

	private void loadConfigFile(Class<?> clazz) throws InstantiationException, IllegalAccessException,
			JsonGenerationException, JsonMappingException, IOException, ServletParseException {

		// start with the default
		boolean noConfigFiles = true;
		T tmp = (T) clazz.newInstance();

		File domainFile = new File(domainPath.toString() + "/" + servletContextName + ".json");
		sipLogger.fine("Attempting to load... " + domainFile.getAbsolutePath());
		try {
			if (domainFile.exists()) {
				sipLogger.fine("Loading... " + domainFile.getAbsolutePath());
				domainNode = mapper.readTree(domainFile);
				sipLogger.fine("domainNode: " + domainNode);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			sipLogger.severe("Error loading config file: " + domainFile.getCanonicalPath());
			sipLogger.severe(e);
		}

		File clusterFile = new File(clusterPath.toString() + "/" + servletContextName + ".json");
		sipLogger.fine("Attempting to load... " + clusterFile.getAbsolutePath());
		try {
			if (clusterFile.exists()) {
				sipLogger.fine("Loading... " + clusterFile.getAbsolutePath());
				clusterNode = mapper.readTree(clusterFile);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			sipLogger.severe("Error loading config file: " + clusterFile.getCanonicalPath());
			sipLogger.severe(e);
		}

		File serverFile = new File(serverPath.toString() + "/" + servletContextName + ".json");
		sipLogger.fine("Attempting to load... " + serverFile.getAbsolutePath());
		try {
			if (serverFile.exists()) {
				sipLogger.fine("Loading... " + serverFile.getAbsolutePath());
				serverNode = mapper.readTree(serverFile);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			sipLogger.severe("Error loading config file: " + serverFile.getCanonicalPath());
			sipLogger.severe(e);
		}

		if (noConfigFiles) {
			current = tmp;
			saveConfigFile(domainFile, current);
		} else {
			sipLogger.fine("Calling mergeCurrentFromJson...");
			mergeCurrentFromJson();
		}

	}

	private void saveSchema() throws JsonGenerationException, JsonMappingException, IOException {
		JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper);
		JsonSchema schema = schemaGen.generateSchema(current.getClass());

		// add title here
		mapper.writerWithDefaultPrettyPrinter()
				.writeValue(new File(schemaPath.toString() + "/" + servletContextName + ".jschema"), schema);

	}

	private void saveConfigFile(File file, T t) throws JsonGenerationException, JsonMappingException, IOException {

		// Never overwrite a .json file, use .SAMPLE instead
		String path = file.getPath();
		if (path.endsWith(".json")) {
			file = new File(path.replace(".json", ".SAMPLE"));
		}

		sipLogger.fine("Saving config to: " + file.getCanonicalPath());

		// save current config to file
		mapper.writerWithDefaultPrettyPrinter().writeValue(file, t);

		// save matching schema to file
		this.saveSchema();
	}

	/**
	 * @return the current
	 */
	public T getCurrent() {
		return current;
	}

	/**
	 * @param current the current to set
	 */
	public void setCurrent(T current) {
		this.current = current;
	}

	public String getCurrentAsJson() {
		String data = null;

		try {
			data = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(current);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}

		return data;
	}

	public void setCurrentFromJson(String json) {
		try {
			sipLogger.warning("Configuration changed...");
			sipLogger.info(json);

			T tmp = mapper.readValue(json, clazz);
			initialize(tmp);
			current = tmp;

		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	public String getJSchema() {
		String strSchema = null;

		try {
			JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper);
			JsonSchema schema = schemaGen.generateSchema(current.getClass());
			// add title here
			strSchema = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
		} catch (JsonProcessingException e) {
			sipLogger.logStackTrace(e);
		}

		return strSchema;
	}

	public String getName() {
		return this.servletContextName;
	}

	public static String getClusterName() {
		return clusterName;
	}

	public static void setClusterName(String clusterName) {
		SettingsManager.clusterName = clusterName;
	}

	public JsonNode merge(JsonNode mainNode, JsonNode updateNode) throws IOException {
		return mapper.readerForUpdating(mainNode).readValue(updateNode);
	}

	/**
	 * Merges the three possible configuration files starting with 'domain' and
	 * applying 'cluster' then 'server'.
	 * 
	 * @throws ServletParseException
	 */
	public void mergeCurrentFromJson() throws ServletParseException {
		try {
			sipLogger.fine("Starting to merge...");
			mergedNode = merge(merge(domainNode, clusterNode), serverNode);

			sipLogger.fine("Merged Node:\n" + mergedNode);

			sipLogger.fine("Starting to convert...");
			T tmp = (T) mapper.convertValue(mergedNode, clazz);
			sipLogger.fine("Starting to initialize...");
			initialize(tmp);
			sipLogger.fine("Assigning current...");
			current = tmp;
			sipLogger.fine("Current: " + current);
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}

	}

	public String getServletContextName() {
		return servletContextName;
	}

	public void setServletContextName(String servletContextName) {
		this.servletContextName = servletContextName;
	}

	public static String getServerName() {
		return serverName;
	}

	public static void setServerName(String serverName) {
		SettingsManager.serverName = serverName;
	}

	public void logCurrent() {
		sipLogger.info("Configuration has changed. New configuration:\n" + getCurrentAsJson());
	}

	/**
	 * Removes the version number from the deployed application name. For instance
	 * an application with the name 'b2bua#2.1.0' would simply be 'b2bua'.
	 * 
	 * @param name
	 * @return
	 */
	public static String basename(String name) {
		int i = name.indexOf('#');
		return (i >= 0) ? name.substring(0, i) : name;
	}

}
