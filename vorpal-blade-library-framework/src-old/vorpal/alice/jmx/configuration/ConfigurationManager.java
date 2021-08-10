package vorpal.alice.jmx.configuration;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.logging.Handler;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.schema.JsonSchema;

import vorpal.alice.logging.Logger;

@Deprecated
@WebListener
public class ConfigurationManager implements ServletContextListener {
	public static Logger logger;
	private static Configuration configuration;
	private ObjectName objectName;
	private ObjectMapper mapper;
	private String filename;
	private String schemaFilename;
	private String sampleFilename;

	public static Object getConfiguration() {
		return configuration.data;
	}

	public void saveConfiguration(Object mbean) throws JsonGenerationException, JsonMappingException, IOException {
		mapper.writerWithDefaultPrettyPrinter().writeValue(new File(sampleFilename), mbean);

		JsonSchema schema = mapper.generateJsonSchema(mbean.getClass());
		mapper.writerWithDefaultPrettyPrinter().writeValue(new File(schemaFilename), schema);

	}

	public Object loadConfiguration(Class<?> clazz)
			throws InstantiationException, IllegalAccessException, JsonGenerationException, JsonMappingException, IOException {
		Object mbean;

		try {
			mbean = mapper.readValue(new File(filename), clazz);
			// logger.fine("Loading configuration...");
			// logger.fine(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mbean));
		} catch (Exception e) {
			// logger.severe(e);
			mbean = clazz.newInstance();
			saveConfiguration(mbean);
		}

		return mbean;
	}

	@Override
	public void contextInitialized(ServletContextEvent event) {
		logger = Logger.getLogger(event.getServletContext());

		try {
			System.out.println(event.getServletContext().getServletContextName() + " starting...");

			String appName = event.getServletContext().getServletContextName();
			filename = "./config/custom/" + appName + ".json";
			schemaFilename = "./config/custom/" + appName + ".jschema";

			sampleFilename = filename + ".SAMPLE";

			// Create Configuration MBean
			String className = event.getServletContext().getInitParameter("vorpal.alice:configuration");
			if (className != null) {
				mapper = new ObjectMapper();
				mapper.configure(SerializationConfig.Feature.WRITE_ENUMS_USING_TO_STRING, true);

				// Load the configuration data if it exists
				Class<?> clazz = Class.forName(className);
				Object data = loadConfiguration(clazz);

				// Create the Configuration MBean
				configuration = new Configuration(clazz, data);

				// Register Configuration MBean
				MBeanServer server = ManagementFactory.getPlatformMBeanServer();
				String name = event.getServletContext().getServletContextName();
				objectName = new ObjectName("vorpal.alice:Name=" + name + ",Type=Configuration");
				ObjectInstance oi = server.registerMBean(configuration, objectName);

				// Save the fully qualified object name
				objectName = oi.getObjectName();
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent event) {

		try {
			if (objectName != null) {
				MBeanServer server = ManagementFactory.getPlatformMBeanServer();
				server.unregisterMBean(objectName);
			}

			for (Handler handler : logger.getHandlers()) {
				handler.close();
			}

		} catch (Exception ex) {
			ex.printStackTrace();
		}

		System.out.println(event.getServletContext().getServletContextName() + " stopped.");

	}

}
