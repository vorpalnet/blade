package vorpal.alice.jmx.configuration;

import java.io.IOException;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.schema.JsonSchema;

import vorpal.alice.logging.Logger;

@Deprecated
public class Configuration implements ConfigurationMXBean {
	private ObjectMapper mapper;
	public Object data;
	private Class<?> clazz;

	public Configuration(Class<?> clazz, Object data) {
		this.clazz = clazz;
		this.data = data;
		mapper = new ObjectMapper();
		mapper.configure(SerializationConfig.Feature.WRITE_ENUMS_USING_TO_STRING, true);
	}

	@Override
	public String getData() {
		String strData = null;

		try {
			strData = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
		} catch (IOException e) {
			e.printStackTrace();
		}

		return strData;

	}

	@Override
	public void setData(String json) {

		try {
			System.out.println("Updating configuration for " + clazz.getName());
			data = mapper.readValue(json, clazz);
		} catch (IOException e) {
			ConfigurationManager.logger.severe(e);
		}

	}

	@Override
	public String getSchema() {
		String strSchema = null;
		JsonSchema schema;

		try {
			schema = mapper.generateJsonSchema(data.getClass());
			strSchema = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return strSchema;
	}

}
