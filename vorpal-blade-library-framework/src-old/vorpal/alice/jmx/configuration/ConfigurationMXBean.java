package vorpal.alice.jmx.configuration;

@Deprecated
public interface ConfigurationMXBean {
	public String getData();

	public void setData(String json);

	public String getSchema();
}
