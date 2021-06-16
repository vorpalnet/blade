package org.vorpal.blade.framework.config;

import java.io.File;
import java.io.IOException;

import javax.servlet.sip.SipServletContextEvent;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.schema.JsonSchema;

import com.fasterxml.jackson.core.type.TypeReference;

public class Settings {

	public String filename;
	public String schemaFilename;
	public String directory;

	public Settings(SipServletContextEvent event) {
		String appName = event.getServletContext().getServletContextName();

		this.directory = "./config/custom/vorpal/";
		this.filename = directory + appName + ".json";
		this.schemaFilename = directory + appName + ".jschema";
	}

	public Object load(Class<?> clazz) throws InstantiationException, IllegalAccessException, JsonGenerationException, JsonMappingException, IOException {
		Object mbean;

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationConfig.Feature.WRITE_ENUMS_USING_TO_STRING, true);

		mbean = mapper.readValue(new File(filename), clazz);

		return mbean;
	}

	public void save(Object settings) throws JsonGenerationException, JsonMappingException, IOException {
		ObjectMapper mapper = new ObjectMapper();
		new File(directory).mkdirs();
		mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filename), settings);
		
		JsonSchema schema = mapper.generateJsonSchema(settings.getClass());
		mapper.writerWithDefaultPrettyPrinter().writeValue(new File(schemaFilename), schema);		
	}

}
