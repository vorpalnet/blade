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
import java.util.Arrays;
import java.util.logging.Level;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipSessionsUtil;
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
import com.kjetland.jackson.jsonSchema.JsonSchemaConfig;
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;
import com.kjetland.jackson.jsonSchema.SubclassesResolver;
import com.kjetland.jackson.jsonSchema.SubclassesResolverImpl;

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
//public class SettingsManager<T> implements ServletContextListener {
public class SettingsManager<T> {
	protected T sample = null;
	protected T current;
	protected ObjectName objectName;
	protected MBeanServer server;
	protected ObjectMapper mapper;
	protected Class<T> clazz;
	protected ObjectInstance oi;
	protected Settings<T> settings;

	protected static String serverName;
	protected static String clusterName;

	protected String servletContextName;
	protected Path domainPath;
	protected Path clusterPath;
	protected Path serverPath;
	protected Path schemaPath;
	protected Path samplePath;

	public static SipFactory sipFactory;
	public static SipSessionsUtil sipUtil;
	public static Logger sipLogger;

	protected JsonNode domainNode = NullNode.getInstance();
	protected JsonNode clusterNode = NullNode.getInstance();
	protected JsonNode serverNode = NullNode.getInstance();
	protected JsonNode mergedNode = NullNode.getInstance();

	public SettingsManager() {

	}

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
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");

		this.mapper = mapper;
		this.build(basename(event.getServletContext().getServletContextName()), clazz, mapper);
	}

	public SettingsManager(ServletContextEvent event, Class<T> clazz, ObjectMapper mapper) {
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");

		this.mapper = mapper;
		this.build(basename(event.getServletContext().getServletContextName()), clazz, mapper);
	}

	public SettingsManager(SipServletContextEvent event, Class<T> clazz) {
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		this.build(basename(event.getServletContext().getServletContextName()), clazz, null);
	}

	public SettingsManager(ServletContextEvent event, Class<T> clazz) {
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		this.build(basename(event.getServletContext().getServletContextName()), clazz, null);
	}

	public SettingsManager(SipServletContextEvent event, Class<T> clazz, T sample) {
		this.sample = sample;
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		this.build(basename(event.getServletContext().getServletContextName()), clazz, null);
	}

	public SettingsManager(ServletContextEvent event, Class<T> clazz, T sample) {
		this.sample = sample;
		sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		sipUtil = (SipSessionsUtil) event.getServletContext().getAttribute("javax.servlet.sip.SipSessionsUtil");
		this.build(basename(event.getServletContext().getServletContextName()), clazz, null);
	}

	public static SipFactory getSipFactory() {
		return sipFactory;
	}

	public static SipSessionsUtil getSipUtil() {
		return sipUtil;
	}

	public static Logger getSipLogger() {
		return sipLogger;
	}

	public static void setSipLogger(Logger sipLogger) {
		SettingsManager.sipLogger = sipLogger;
	}

	public String getDomainJson() throws JsonProcessingException {
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(domainNode);
	}

	public void setDomainJson(String domainJson) throws JsonMappingException, JsonProcessingException, IOException {
		domainNode = mapper.readTree(domainJson);
	}

	public String getServerJson() throws JsonProcessingException {
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(serverNode);
	}

	public void setServerJson(String serverJson) throws JsonMappingException, JsonProcessingException, IOException {
		serverNode = mapper.readTree(serverJson);
	}

	public String getClusterJson() throws JsonProcessingException {
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(clusterNode);
	}

	public void setClusterJson(String clusterJson) throws JsonMappingException, JsonProcessingException, IOException {
		clusterNode = mapper.readTree(clusterJson);
	}

	public void build(String name, Class<T> clazz, ObjectMapper _mapper) {
		this.servletContextName = basename(name);
		sipLogger = LogManager.getLogger(servletContextName);

		this.clazz = clazz;

		try {

			if (null == _mapper) {
				this.mapper = new ObjectMapper();
			} else {
				this.mapper = _mapper;
			}

			AsyncSipServlet.setSipLogger(sipLogger);
			Callflow.setLogger(sipLogger);

			settings = new Settings<T>(clazz, this, name, mapper, sample);

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
			serverPath = Paths.get(configPath + "_servers/" + serverName);
			Files.createDirectories(serverPath);
			ObjectName managedServerName = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
			ObjectName clusterObjectName = (ObjectName) server.getAttribute(managedServerName, "Cluster");
			if (clusterObjectName != null) {
				clusterName = (String) server.getAttribute(clusterObjectName, "Name");
				clusterPath = Paths.get(configPath + "_clusters/" + clusterName);
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

			// Trying to get rid of
			// WARNING: Not able to generate jsonSchema-info for type: [simple type, class
			// java.lang.Object] - probably using custom serializer which does not override
			// acceptJsonFormatVisitor

			// this.mapper.registerModule(new SimpleModule()//
			// .addSerializer(Object.class, new JsonObjectSerializer()));

			// Don't bother to save attributes set to null.
			this.mapper.setSerializationInclusion(Include.NON_NULL);

			this.mapper.configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false);

			if (clusterName != null) {
				objectName = new ObjectName(
						"vorpal.blade:Name=" + servletContextName + ",Type=Configuration,Cluster=" + clusterName);
			} else {
				objectName = new ObjectName("vorpal.blade:Name=" + servletContextName + ",Type=Configuration");
			}

			register();

			settings.reload();

			this.current = (T) settings.getConfiguration();

			if (current != null) {
				this.saveSchema(current);
			}

			if (sample != null) {
				this.saveConfigFile(sample);
			} else {
				this.saveConfigFile(current);
			}

		} catch (Exception e) {
			e.printStackTrace();
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

		sipLogger.fine("Registering MBean: " + objectName.toString());
		oi = server.registerMBean(settings, objectName);
		// what is this for?
		objectName = oi.getObjectName();
		sipLogger.fine("object name is now: " + objectName.toString());

	}

	public void unregister() throws MBeanRegistrationException, InstanceNotFoundException {
		server.unregisterMBean(objectName);
	}

	public void saveSchema(T t) throws JsonGenerationException, JsonMappingException, IOException {
		SubclassesResolver resolver = new SubclassesResolverImpl().withClassesToScan(Arrays.asList(clazz.getName()));
		JsonSchemaConfig config = JsonSchemaConfig.html5EnabledSchema().withSubclassesResolver(resolver);
		JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(mapper, config);
		JsonNode jsonSchema = jsonSchemaGenerator.generateJsonSchema(t.getClass());
		File schemaFile = new File(schemaPath.toString() + "/" + servletContextName + ".jschema");
		mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile, jsonSchema);
	}

	public void saveConfigFile(T t) throws JsonGenerationException, JsonMappingException, IOException {

		if (t != null) {
			File configFile = new File(samplePath.toString() + "/" + servletContextName + ".json.SAMPLE");
			mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, t);
		} else {
			throw new IOException("Sample config file is null! How did this happen!?");
		}

	}

	/**
	 * @return the current
	 */
	public T getCurrent() {
		return current;
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

			Level level = sipLogger.getConfigurationLoggingLevel();
			if (level == null) {
				level = Level.FINE;
			}

			sipLogger.log(level, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this.getCurrent()));

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
