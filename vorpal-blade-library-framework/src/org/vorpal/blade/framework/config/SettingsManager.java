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

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.vorpal.blade.framework.callflow.Callflow;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;

/**
 * @author Jeff McDonald
 *
 */
public class SettingsManager<T> {
	protected T current;
	protected ObjectName objectName;
	protected String servletContextName;
	protected MBeanServer server;

	public String filename;
	public String sampleFilename;
	public String schemaFilename;
	public String directory;
	protected ObjectMapper mapper;
	protected Class<T> clazz;
	protected ObjectInstance oi;
	protected Settings settings;

	public SettingsManager(String name, Class<T> clazz) {
		this.servletContextName = name;
		this.clazz = clazz;
		this.directory = "./config/custom/vorpal/";
		this.filename = directory + servletContextName + ".json";
		this.sampleFilename = directory + servletContextName + ".SAMPLE";
		this.schemaFilename = directory + "schemas/" + servletContextName + ".jschema";

		try {
			new File(directory + "/schemas/").mkdirs();
			mapper = new ObjectMapper();
			server = ManagementFactory.getPlatformMBeanServer();
			objectName = new ObjectName("vorpal.blade:Name=" + servletContextName + ",Type=Configuration");
			register();
		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
		}
	}

	/**
	 * This method is intended to be overridden to allow configurations that require
	 * additional work before they are ready to use.
	 */
	public void intialize(T config) {

	}

	public void register() throws InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException,
			JsonGenerationException, JsonMappingException, InstantiationException, IllegalAccessException, IOException {
		this.settings = new Settings(this);
		loadConfigFile(clazz);

		Callflow.getLogger().severe("Registering MXBean " + objectName);

		oi = server.registerMBean(this.settings, objectName);
		objectName = oi.getObjectName();

		Callflow.getLogger().severe("Changing object name to " + objectName);

	}

	public void unregister() throws MBeanRegistrationException, InstanceNotFoundException {
		server.unregisterMBean(objectName);
	}

	private void loadConfigFile(Class<?> clazz) throws InstantiationException, IllegalAccessException,
			JsonGenerationException, JsonMappingException, IOException {
//		mapper.configure(SerializationConfig.Feature.WRITE_ENUMS_USING_TO_STRING, true);
		// add save config example code

		try {
			current = (T) mapper.readValue(new File(filename), clazz);
		} catch (Exception e) {
			current = (T) clazz.newInstance();
			saveConfigFile();
		}
	}

	private void saveConfigFile() throws JsonGenerationException, JsonMappingException, IOException {

		// save current config to file
		mapper.writerWithDefaultPrettyPrinter().writeValue(new File(sampleFilename), current);

		// save matching schema to file
		JsonSchemaGenerator schemaGen = new JsonSchemaGenerator(mapper);
		JsonSchema schema = schemaGen.generateSchema(current.getClass());
		// add title here
		mapper.writerWithDefaultPrettyPrinter().writeValue(new File(schemaFilename), schema);

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
			Callflow.getLogger().warning("Configuration changed...");
			Callflow.getLogger().info(json);

			T tmp = mapper.readValue(json, clazz);
			intialize(tmp);
			current = tmp;

		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
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
			Callflow.getLogger().logStackTrace(e);
		}

		return strSchema;
	}

	public String getName() {
		return this.servletContextName;
	}

}
