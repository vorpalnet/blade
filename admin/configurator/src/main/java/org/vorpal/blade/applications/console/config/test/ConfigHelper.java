package org.vorpal.blade.applications.console.config.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.vorpal.blade.framework.v2.config.SettingsMXBean;

public class ConfigHelper {

	private String app;
	private String configType;

	private Path schemaPath;
	private Path domainPath;
	private Path clusterPath;
	private Path serverPath;
	private Path samplePath;

	private SettingsMXBean settings;
	private InitialContext ctx = null;

	public ConfigHelper(String app, String configType) {

		this.app = app;
		this.configType = (configType != null) ? configType : "domain";

		schemaPath = Paths.get("config/custom/vorpal/_schemas/" + app + ".jschema");
		domainPath = Paths.get("config/custom/vorpal/" + app + ".json");
		clusterPath = Paths.get("config/custom/vorpal/_clusters/" + app + ".json");
		serverPath = Paths.get("config/custom/vorpal/_servers/" + app + ".json");
		samplePath = Paths.get("config/custom/vorpal/_samples/" + app + ".json.SAMPLE");

	}

	public ConfigHelper(String app) {
		this(app, null);
	}

	public Path getPath(String configType) {
		Path path = null;

		switch (configType) {
		case "DOMAIN":
		case "Domain":
		case "domain":
			path = domainPath;
			break;
		case "CLUSTER":
		case "Cluster":
		case "cluster":
			path = clusterPath;
			break;

		case "SERVER":
		case "Server":
		case "server":
			path = serverPath;
			break;

		case "SCHEMA":
		case "Schema":
		case "schema":
			path = schemaPath;
			break;

		case "SAMPLE":
		case "Sample":
		case "sample":
			path = samplePath;
			break;

		}

		return path;
	}

	public void saveFileLocally(String configType, String json) {

		try {

			Path path = getPath(configType);

			System.out.println("Saving " + path.getFileName());
			System.out.println(json);

			if (json != null && json.length() > 0) {

				// java 11
				// Files.writeString(path, json, StandardOpenOption.CREATE,
				// StandardOpenOption.TRUNCATE_EXISTING);

				// java 1.8
				Files.write(path, json.getBytes());

			}

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void closeSettings() {

		if (ctx != null) {
			try {
				ctx.close();
			} catch (NamingException e) {
				// who cares?
			}
		}

	}

	public void getSettings() {

		InitialContext ctx = null;
		try {
			ctx = new InitialContext();
			MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName objectName = null;
			objectName = new ObjectName("vorpal.blade:Name=" + app + ",Type=Configuration,*");

			Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);
			if (mbeans != null) {
				System.out.println("Found " + mbeans.size());
			} else {
				System.out.println("No app named " + app + " found.");
			}

			ObjectInstance mbean = mbeans.iterator().next();
			ObjectName name = mbean.getObjectName();
			this.settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public String loadFile(String configType) throws IOException {

		long localTimestamp = 0;
		long remoteTimestamp = 0;
		StringBuffer strBuffer = new StringBuffer();
		String line = null;
		Path path = null;

		path = getPath(configType);

		if (path != null) {

			if (Files.exists(path)) {
				BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
				localTimestamp = attr.lastModifiedTime().toMillis();
			}

			try {
				ctx = new InitialContext();
				MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
				ObjectName objectName = null;
				objectName = new ObjectName("vorpal.blade:Name=" + app + ",Type=Configuration,*");

				Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);
				if (mbeans == null) {
					System.out.println("No app named " + app + " found.");
					return "";
				}

				ObjectInstance mbean = mbeans.iterator().next();
				ObjectName name = mbean.getObjectName();
				SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);
				remoteTimestamp = settings.getLastModified(configType);

				System.out.println("testing begin...");
				System.out.println("path exists? " + (path != null));
				System.out.println("path " + path);
				System.out.println("path.toFile().exists..? " + path.toFile().exists());
				System.out.println("path.toFile().exists again..? " + path.toFile().exists());
				System.out.println("testing end.");

				if (false == path.toFile().exists() || (remoteTimestamp > localTimestamp)) {
					// read the file remotely
//					settings.openForRead(configType);
//					while (null != (line = settings.read())) {
//						strBuffer.append(line);
//					}
//					settings.close();					
//					if (strBuffer.length() > 0) {
//						System.out.println(
//								"Saving file " + path.getFileName() + " locally... bytes: " + strBuffer.length());
//						saveFileLocally(configType, strBuffer.toString());
//					}

				} else {
					// read the file locally

					BufferedReader bufferedReader = Files.newBufferedReader(path);
					while (null != (line = bufferedReader.readLine())) {
						strBuffer.append(line);
					}
					bufferedReader.close();

					System.out
							.println("Loading file " + path.getFileName() + " locally... bytes: " + strBuffer.length());
				}

			} catch (Exception e) {
				e.printStackTrace();
			}

		}

		return strBuffer.toString();

	}

	public Set<String> listFilesUsingFilesList(String dir) throws IOException {
		try (Stream<Path> stream = Files.list(Paths.get(dir))) {
			return stream.filter(file -> !Files.isDirectory(file)).map(Path::getFileName).map(Path::toString)
					.collect(Collectors.toSet());
		}

	}

}
