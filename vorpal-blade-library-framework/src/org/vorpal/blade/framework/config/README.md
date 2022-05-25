# Vorpal:BLADE Framework Config

See Javadocs: [org.vorpal.blade.framework.config](https://vorpalnet.github.io/vorpal-blade-library-framework/index.html?org/vorpal/blade/framework/config/package-summary.html)

The 'config' package offers some nifty utilities to create and manage configuration files.

Here's an example of how to use it.

1. Deploy the "vorpal-blade-console.war" file to the Admin server.
It will monitor any changes to config files and notify applications running in the engine tiers.

1. Create any ordinary Java (POJO) class to be your config file. Make sure that it implements the
Serializable interface.

Example:

```
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


1. Next, use the
[Settings](https://vorpalnet.github.io/vorpal-blade-library-framework/index.html?org/vorpal/blade/framework/config/Settings.html)
class to turn your POJO into a configuration file.

Example:

```
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

What's going here? Basically, we created a public data member "settingsManager" by passing in the SipServletContextEvent which
will name the config file based on the ServletContext name ("keep-alive" in this case.)

Upon startup of the application, the BLADE framework will create a configuration file:

./config/custom/vorpal/keep-alive.SAMPLE

This will be created in the domain of the engine tier node hosting the application. To use it,
copy the file to the same location in the Admin server's domain directory and rename it with a '.json' file extension.

In addition, you can create additional copies of the file for the specific cluster or server the application is deployed in.

Example:

* ./config/custom/vorpal/keep-alive.json
* ./config/custom/vorpal/cluster/BEA_ENGINE_TIER_CLUS/keep-alive.json
* ./config/custom/vorpal/server/engine1/keep-alive.json

If you define multiple config files, the BLADE framework with merge the JSON files together, giving preference to 'server', 'cluster' and
'domain' in that order. (The 'domain' config file in this case is: ./config/custom/vorpal/keep-alive.json.)

You can now edit the config file (on the Admin server) on the fly and the BLADE console application will detect the change and
relay that information to the proper clustered application through JMX MBeans. Cool!

1. Now that you've created a config file and can manage them, you need to use it in your code.

Consider this simple example:

```
KeepAliveConfig config = settingsManager.getCurrent();
System.out.println("SessionExpires: "+config.getSessionExpires());

```
Somewhere in your code, invoke the .getCurrent() method to return your POJO as defined by the JSON config files.
The method .getCurrent() will return the latest configuration. In your SIP callflows, try to call it only once
at the beginning of the callflow. This will preserve data integrity
in case the config changes in mid callflow. Also, try to save only the data required as variables.
You don't want to store unnecessary data in session memory, especially if the callflow is huge.





