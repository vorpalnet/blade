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

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.logging.LogManager2;
import org.vorpal.blade.framework.logging.LogParameters;
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
public class SettingsManager2<T> {
	private T sample = null;
	private T current;
	private ObjectName objectName;
	private MBeanServer server;
	private ObjectMapper mapper;
	private Class<T> clazz;
	private ObjectInstance oi;
	private Settings settings;
	private String name;

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

	public static SipFactory getSipFactory() {
		return sipFactory;
	}

	public static void setSipFactory(SipFactory _sipFactory) {
		sipFactory = _sipFactory;
	}

	public static Logger getSipLogger() {
		return sipLogger;
	}

	public static void setSipLogger(Logger _sipLogger) {
		sipLogger = _sipLogger;
	}

	public String getDomainJson() throws JsonProcessingException {
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(domainNode);
	}

	public void setDomainJson(String domainJson) throws JsonMappingException, JsonProcessingException {
		domainNode = mapper.readTree(domainJson);
	}

	public String getServerJson() throws JsonProcessingException {
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(serverNode);
	}

	public void setServerJson(String serverJson) throws JsonMappingException, JsonProcessingException {
		serverNode = mapper.readTree(serverJson);
	}

	public String getClusterJson() throws JsonProcessingException {
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(clusterNode);
	}

	public void setClusterJson(String clusterJson) throws JsonMappingException, JsonProcessingException {
		clusterNode = mapper.readTree(clusterJson);

	}

	public SettingsManager2(String name, Class<T> clazz, ObjectMapper mapper) {
		this.mapper = mapper;
		this.name = name;

		this.build(null, clazz, null, mapper);
	}

	public SettingsManager2(String name, Class<T> clazz) {
		this.name = name;
		this.build(null, clazz, null, null);
	}

	public SettingsManager2(String name, Class<T> clazz, T sample) {
		this.name = name;
		this.build(null, clazz, sample, null);
	}

	public SettingsManager2(String name, Class<T> clazz, T sample, ObjectMapper mapper) {
		this.name = name;
		this.build(null, clazz, sample, mapper);
	}

	public SettingsManager2(SipServletContextEvent event, Class<T> clazz, ObjectMapper mapper) {
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		this.mapper = mapper;
		this.build(event, clazz, null, mapper);
	}

	public SettingsManager2(SipServletContextEvent event, Class<T> clazz) {
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		this.build(event, clazz, null, null);
	}

	public SettingsManager2(SipServletContextEvent event, Class<T> clazz, T sample) {
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		this.build(event, clazz, sample, null);
	}

	public void build(SipServletContextEvent event, Class<T> clazz, T sample, ObjectMapper _mapper) {

		if (this.name != null) {
			this.servletContextName = basename(this.name);
		} else {
			if (event != null) {
				this.servletContextName = basename(event.getServletContext().getServletContextName());
			} else {
				System.out.println("SettingsManager is unable to determine 'name'. Using default.");
				this.servletContextName = "DEFAULT";
			}

		}

		this.clazz = clazz;
		this.sample = sample;

		try {

			if (null == _mapper) {
				this.mapper = new ObjectMapper();
			} else {
				this.mapper = _mapper;
			}

		
			//jwm -- add this back in
			// settings = new Settings(this);

			
			
			
			
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

			this.mapper.registerModule(new SimpleModule().addDeserializer(URI.class, new JsonUriDeserializer()));
			this.mapper
					.registerModule(new SimpleModule().addDeserializer(Address.class, new JsonAddressDeserializer()));
			this.mapper.registerModule(
					new SimpleModule().addDeserializer(IPAddress.class, new JsonIPAddressDeserializer()));
			this.mapper.registerModule(new SimpleModule().addSerializer(URI.class, new JsonUriSerializer()));
			this.mapper.registerModule(new SimpleModule().addSerializer(Address.class, new JsonAddressSerializer()));
			this.mapper
					.registerModule(new SimpleModule().addSerializer(IPAddress.class, new JsonIPAddressSerializer()));
			this.mapper.registerModule(
					new SimpleModule().addKeyDeserializer(inet.ipaddr.Address.class, new InetAddressKeyDeserializer()));

			// Don't bother to save attributes set to null.
			this.mapper.setSerializationInclusion(Include.NON_NULL);

			if (clusterName != null) {
				objectName = new ObjectName(
						"vorpal.blade:Name=" + servletContextName + ",Type=Configuration,Cluster=" + clusterName);
			} else {
				objectName = new ObjectName("vorpal.blade:Name=" + servletContextName + ",Type=Configuration");
			}
			register();

			// Last and final step is to build the Logger
			if (current instanceof Configuration) {
				sipLogger = LogManager2.getLogger(event.getServletContext(), ((Configuration) current).getLogging());
			} else {
				if (event != null) {
					sipLogger = LogManager2.getLogger(event.getServletContext(), new LogParameters());
				} else {
					sipLogger = LogManager2.getLogger(this.servletContextName);
				}
			}
			AsyncSipServlet.setSipLogger(sipLogger);
			Callflow.setLogger(sipLogger);

		} catch (Exception e) {
			e.printStackTrace();
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
			System.out.println("Error loading config file: " + domainFile.getCanonicalPath());
			e.printStackTrace();
		}

		File clusterFile = new File(clusterPath.toString() + "/" + servletContextName + ".json");
		try {
			if (clusterFile.exists()) {
				clusterNode = mapper.readTree(clusterFile);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			System.out.println("Error loading config file: " + clusterFile.getCanonicalPath());
			e.printStackTrace();
		}

		File serverFile = new File(serverPath.toString() + "/" + servletContextName + ".json");
		try {
			if (serverFile.exists()) {
				serverNode = mapper.readTree(serverFile);
				noConfigFiles = false;
			}
		} catch (Exception e) {
			System.out.println("Error loading config file: " + serverFile.getCanonicalPath());
			e.printStackTrace();
		}

		if (noConfigFiles) {
			current = tmp;
		} else {
			mergeCurrentFromJson();
		}

	}

	private void saveConfigFile(T t) throws JsonGenerationException, JsonMappingException, IOException {
		File configFile = new File(samplePath.toString() + "/" + servletContextName + ".json.SAMPLE");
		mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, t);

		File schemaFile = new File(schemaPath.toString() + "/" + servletContextName + ".jschema");
		JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper);
		JsonSchema schema = schemaGen.generateSchema(t.getClass());
		mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile, schema);
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
			T tmp = mapper.readValue(json, clazz);
			initialize(tmp);
			current = tmp;

		} catch (Exception e) {
			e.printStackTrace();
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
			e.printStackTrace();
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
		SettingsManager2.clusterName = clusterName;
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
			current = tmp;
		} catch (Exception e) {
			e.printStackTrace();
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
		SettingsManager2.serverName = serverName;
	}

	public void logCurrent() {
		if (sipLogger != null) {
			sipLogger.info("Configuration has changed...\n" + getCurrentAsJson());
		} else {
			System.out.println("Configuration has changed...\n" + getCurrentAsJson());
		}
	}

	/**
	 * Removes the version number from the deployed application name. For instance
	 * an application with the name 'b2bua#2.1.0' would simply be 'b2bua'.
	 * 
	 * @param name
	 * @return the base name of the application
	 */
	public static String basename(String name) {
		int i = name.indexOf('#');
		return (i >= 0) ? name.substring(0, i) : name;
	}

}
