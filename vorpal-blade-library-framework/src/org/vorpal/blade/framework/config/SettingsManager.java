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
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.NullNode;
//import com.kjetland.jackson.jsonSchema.JsonSchemaConfig;
//import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;

import inet.ipaddr.IPAddress;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv6.IPv6Address;

/**
 * The SettingsManager class automatically reads a JSON formated configuration
 * file. Actually, there can be three configuration files, one for the DOMAIN,
 * CLUSTER, and MACHINE. All three files are merged together.
 * <p>
 * A fourth, SAMPLE, configuration file is automatically generated and saved for
 * ease of use. (You can find it in the 'config/custom/vorpal/samples'
 * directory.
 * <p>
 * Any serializable class (POJO) can be used as a config file. Extending the
 * class from the Configuration class will provide extra benefits, like
 * controlling the level of logging.
 * 
 * @author Jeff McDonald
 * @param <T> any type of serializable class
 *
 */
// @WebListener
// public class SettingsManager<T> implements ServletContextListener {
public class SettingsManager<T> {
	private T sample = null;
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
	protected Path samplePath;

	public static SipFactory sipFactory;
	public static Logger sipLogger;

	private JsonNode domainNode = NullNode.getInstance();
	private JsonNode clusterNode = NullNode.getInstance();
	private JsonNode serverNode = NullNode.getInstance();
	private JsonNode mergedNode = NullNode.getInstance();

//	@Override
//	public void contextInitialized(ServletContextEvent event) {
////		sipLogger = LogManager.getLogger(event.getServletContext());
//	}
//
//	@Override
//	public void contextDestroyed(ServletContextEvent event) {
//		LogManager.closeLogger(event);
//	}

	public SettingsManager(String name, Class<T> clazz, ObjectMapper mapper) {
		this.mapper = mapper;
		this.build(name, clazz, mapper);
	}

	public SettingsManager(String name, Class<T> clazz) {
		this.build(name, clazz, null);
	}

	public SettingsManager(String name, Class<T> clazz, T sample) {
		this.sample = sample;
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

	public SettingsManager(SipServletContextEvent event, Class<T> clazz, T sample) {
		this.sample = sample;
		this.sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		this.build(basename(event.getServletContext().getServletContextName()), clazz, null);
	}

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

	public void build(String name, Class<T> clazz, ObjectMapper _mapper) {

		this.servletContextName = basename(name);
		this.clazz = clazz;

		try {

			if (null == _mapper) {
				this.mapper = new ObjectMapper();
			} else {
				this.mapper = _mapper;
			}

			sipLogger = LogManager.getLogger(name);

			AsyncSipServlet.setSipLogger(sipLogger);
			Callflow.setLogger(sipLogger);

			settings = new Settings(this);

			// Get the managed server & cluster names
			String configPath = "config/custom/vorpal/";
			domainPath = Paths.get(configPath);
			Files.createDirectories(domainPath);
			schemaPath = Paths.get(configPath + "_schemas/");
			Files.createDirectories(schemaPath);
			samplePath = Paths.get(configPath + "_samples/");
			Files.createDirectories(samplePath);
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

			// URI
			this.mapper.registerModule(new SimpleModule()//
					.addSerializer(URI.class, new JsonUriSerializer()));
			this.mapper.registerModule(new SimpleModule()//
					.addDeserializer(URI.class, new JsonUriDeserializer()));

			// SipURI
			this.mapper.registerModule(new SimpleModule()//
					.addSerializer(SipURI.class, new JsonSipUriSerializer()));
			this.mapper.registerModule(new SimpleModule()//
					.addDeserializer(SipURI.class, new JsonSipUriDeserializer()));

			// Address
			this.mapper.registerModule(new SimpleModule()//
					.addSerializer(Address.class, new JsonAddressSerializer()));
			this.mapper.registerModule(new SimpleModule()//
					.addDeserializer(Address.class, new JsonAddressDeserializer()));

			// IPAddress
			this.mapper.registerModule(new SimpleModule()//
					.addSerializer(IPAddress.class, new JsonIPAddressSerializer()));
			this.mapper.registerModule(new SimpleModule()//
					.addDeserializer(IPAddress.class, new JsonIPAddressDeserializer()));

			// IPv4Address
			this.mapper.registerModule(new SimpleModule()//
					.addSerializer(IPv4Address.class, new JsonIPv4AddressSerializer()));
			this.mapper.registerModule(new SimpleModule()//
					.addDeserializer(IPv4Address.class, new JsonIPv4AddressDeserializer()));

			// IPv6Address
			this.mapper.registerModule(new SimpleModule()//
					.addSerializer(IPv6Address.class, new JsonIPv6AddressSerializer()));
			this.mapper.registerModule(new SimpleModule()//
					.addDeserializer(IPv6Address.class, new JsonIPv6AddressDeserializer()));

			this.mapper.registerModule(new SimpleModule()//
					.addKeyDeserializer(inet.ipaddr.Address.class, new InetAddressKeyDeserializer()));

			// Don't both to save attributes set to null.
			this.mapper.setSerializationInclusion(Include.NON_NULL);

			this.mapper.configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false);

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
	 * @param config
	 * @throws ServletParseException
	 */
	public void initialize(T config) throws ServletParseException {
		// do nothing;
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

	public void reloadConfigFiles() throws InstantiationException, IllegalAccessException, JsonGenerationException,
			JsonMappingException, IOException, ServletParseException {

		loadConfigFile(clazz);

	}

	@SuppressWarnings("unchecked")
	private void loadConfigFile(Class<?> clazz) throws InstantiationException, IllegalAccessException,
			JsonGenerationException, JsonMappingException, IOException, ServletParseException {

		// start with the default
		boolean noConfigFiles = true;

		T tmp;
		if (sample == null) {
			tmp = (T) clazz.newInstance();
		} else {
			tmp = sample;
		}

		// always save the current schema and sample config file
		saveConfigFile(tmp);

		File domainFile = new File(domainPath.toString() + "/" + servletContextName + ".json");
		try {
			if (domainFile.exists()) {
				domainNode = mapper.readTree(domainFile);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			sipLogger.severe("Error loading config file: " + domainFile.getCanonicalPath());
			e.printStackTrace();
		}

		File clusterFile = new File(clusterPath.toString() + "/" + servletContextName + ".json");
		try {
			if (clusterFile.exists()) {
				clusterNode = mapper.readTree(clusterFile);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			sipLogger.severe("Error loading config file: " + clusterFile.getCanonicalPath());
			e.printStackTrace();
		}

		File serverFile = new File(serverPath.toString() + "/" + servletContextName + ".json");
		try {
			if (serverFile.exists()) {
				serverNode = mapper.readTree(serverFile);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			sipLogger.severe("Error loading config file: " + serverFile.getCanonicalPath());
			e.printStackTrace();
		}

		if (noConfigFiles) {

			if (this.sample != null) {
				sipLogger.fine("using sample...");

				current = this.sample;

			} else {
				sipLogger.fine("using default...");
				current = tmp;

			}

			this.initialize(current);

			this.logCurrent(); // show 'em what you got

		} else {
			sipLogger.fine("using config...");
			mergeCurrentFromJson(); // logs the config file
		}

	}

	private void saveSchema() throws JsonGenerationException, JsonMappingException, IOException {

//		JsonSchemaConfig config = JsonSchemaConfig.nullableJsonSchemaDraft4().html5EnabledSchema();
//		JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper, config);
//		JsonNode jsonSchema = schemaGen.generateJsonSchema(current.getClass());
//
//		// add title here
//		mapper.writerWithDefaultPrettyPrinter()
//				.writeValue(new File(schemaPath.toString() + "/" + servletContextName + ".jschema"), jsonSchema);

	}

	private void saveConfigFile(T t) throws JsonGenerationException, JsonMappingException, IOException {
		File configFile = new File(samplePath.toString() + "/" + servletContextName + ".json.SAMPLE");
		mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, t);

//		File schemaFile = new File(schemaPath.toString() + "/" + servletContextName + ".jschema");
//		JsonSchemaConfig config = JsonSchemaConfig.nullableJsonSchemaDraft4().html5EnabledSchema();
//		JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper, config);
//		JsonNode schema = schemaGen.generateJsonSchema(t.getClass());
//		mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile, schema);
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
			sipLogger.info("setCurrentFromJson...");
			sipLogger.info("Configuration changed...");
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

//		try {
//			JsonSchemaConfig config = JsonSchemaConfig.nullableJsonSchemaDraft4().html5EnabledSchema();
//			JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper, config);
//			JsonNode schema = schemaGen.generateJsonSchema(current.getClass());
//			// add title here
//			strSchema = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
//		} catch (JsonProcessingException e) {
//			sipLogger.logStackTrace(e);
//		}

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
			mergedNode = merge(merge(domainNode, clusterNode), serverNode);
			T tmp = (T) mapper.convertValue(mergedNode, clazz);
			initialize(tmp);

			System.out.println("This is where you update the logging settings.");
			sipLogger.severe("This is where you update the logging settings.");

			if (sipLogger != null) {
				if (tmp instanceof Configuration) {
					Configuration config = (Configuration) tmp;

					if (config.getLogging() != null //
							&& config.getLogging().resolveUseParentLogging() == false //
							&& config.getLogging().resolveLoggingLevel() != null) {
						sipLogger.setLevel(config.getLogging().resolveLoggingLevel());
						sipLogger.log(config.getLogging().resolveLoggingLevel(),
								"Setting logging level to: " + config.getLogging().resolveLoggingLevel());

						sipLogger.setConfigurationLoggingLevel(config.getLogging().resolveConfigurationLoggingLevel());
						sipLogger.setSequenceDiagramLoggingLevel(
								config.getLogging().resolveSequenceDiagramLoggingLevel());
					}

				}

				this.logCurrent();

			} else {
				System.out.println("Settings.setDomainJson could not get sipLogger.");
			}

			current = tmp;
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

	/**
	 * Logs the current configuration.
	 */
	public void logCurrent() {

		try {
//			sipLogger.log(this.getSipLogger().getConfigurationLoggingLevel(), "Configuration has changed:\n"
//					+ mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this.getCurrent()));

			sipLogger.log(this.getSipLogger().getLevel(), "Configuration has changed:\n"
					+ mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this.getCurrent()));

		} catch (JsonProcessingException e) {
			sipLogger.severe(e);
		}

	}

	/**
	 * Removes the version number from the deployed application name. For instance
	 * an application with the name 'b2bua#2.1.0' would simply be 'b2bua'.
	 * 
	 * @param name
	 * @return The base name of the application
	 */
	public static String basename(String name) {
		int i = name.indexOf('#');
		return (i >= 0) ? name.substring(0, i) : name;
	}

}
