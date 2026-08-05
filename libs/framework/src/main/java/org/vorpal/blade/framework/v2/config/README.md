# BLADE Framework Config (v2)

Javadocs: package `org.vorpal.blade.framework.v2.config` — browse at `/blade/javadoc/framework/` on the Admin Portal

The 'config' package turns a plain Java class into a live, hot-reloadable JSON configuration file.

Here's how to use it.

1. Create any ordinary Java (POJO) class to be your config file. Make sure that it implements the
Serializable interface.

Example:

```java
public class KeepAliveConfig implements Serializable {
	private int sessionExpires = 1800; // 30 minutes
	private int minSE = 90; // 1.5 minutes

	public int getSessionExpires() {
		return sessionExpires;
	}

	public void setSessionExpires(int sessionExpires) {
		this.sessionExpires = sessionExpires;
	}

	public int getMinSE() {
		return minSE;
	}

	public void setMinSE(int minSE) {
		this.minSE = minSE;
	}

}
```

This example is taken from the Keep-Alive application. As you can tell, it only does two things: get and set the 'sessionExpires' and 'minSE' variables.

You'll notice it contains default values. This is helpful, because when the configuration file is created, it
will contain some example data.

2. Next, use the
`SettingsManager`
class to turn your POJO into a configuration file.

Example:

```java
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class KeepAliveServlet extends B2buaServlet implements SipApplicationSessionListener {
	public static SettingsManager<KeepAliveConfig> settingsManager;

	@Override
	public void servletCreated(SipServletContextEvent event) {
		settingsManager = new SettingsManager<>(event, KeepAliveConfig.class);
	}
...	
	
```

What's going on here? We created a public data member "settingsManager" by passing in the SipServletContextEvent, which
names the config file after the ServletContext name ("keep-alive" in this case).

Upon startup of the application, the BLADE framework will create a sample configuration file:

./config/custom/vorpal/_samples/keep-alive.json.SAMPLE

This is created in the domain directory of the engine tier node hosting the application,
alongside the generated JSON Schema in `./config/custom/vorpal/_schemas/keep-alive.jschema`.

The normal way to create and edit the live configuration is the
[Configurator](../../../../../../../../../../../admin/configurator/README.md) — it discovers the
application's JSON Schema over JMX, presents a generated form, keeps version history, and
publishes the config to the running cluster with one click. No restarts.

You can also manage the files by hand: copy the SAMPLE file out of `_samples/` into the
domain directory below and drop the `.SAMPLE` suffix, leaving a `.json` extension. You can
create additional copies of the file scoped to a specific cluster or server:

* ./config/custom/vorpal/keep-alive.json
* ./config/custom/vorpal/_clusters/BEA_ENGINE_TIER_CLUST/keep-alive.json
* ./config/custom/vorpal/_servers/engine1/keep-alive.json

The underscore-prefixed directory names are exact — `_clusters` and `_servers`, both plural.
The framework creates them at startup (`SettingsManager.initConfigPaths`); a file placed
anywhere else is simply never read, and the override silently does nothing.

If you define multiple config files, the BLADE framework merges them: it reads the domain
file, overlays the cluster file on top, then overlays the server file. Preference therefore
runs 'server', 'cluster', 'domain' — the narrowest scope wins. (The 'domain' config file in
this case is: ./config/custom/vorpal/keep-alive.json.) Overlaying is per-field, so a cluster
or server file need only carry the settings it changes.

3. Now that you've created a config file, you need to use it in your code.

Consider this simple example:

```java
KeepAliveConfig config = settingsManager.getCurrent();
sipLogger.info("SessionExpires: " + config.getSessionExpires());
```

Somewhere in your code, invoke the .getCurrent() method to return your POJO as defined by the JSON config files.
The method .getCurrent() will return the latest configuration. In your SIP callflows, try to call it only once
at the beginning of the callflow. This preserves data integrity
in case the config changes in mid callflow. Also, try to save only the data you need as variables.
You don't want to store unnecessary data in session memory, especially if the callflow is huge.

## See also

- [v2 API overview](../README.md)
- [Configurator](../../../../../../../../../../../admin/configurator/README.md) — browser-based config editing and publishing
- [v3 API](../../v3/README.md) — the v3 configuration model (two-phase routing, schema-driven forms)
