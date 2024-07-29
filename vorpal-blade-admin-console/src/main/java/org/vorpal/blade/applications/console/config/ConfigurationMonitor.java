package org.vorpal.blade.applications.console.config;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.vorpal.blade.framework.config.SettingsMXBean;

public class ConfigurationMonitor extends Thread {

	private WatchService watcher;
	private Map<WatchKey, Path> keys;
	private boolean recursive = true;
	private boolean trace = false;
	// private Path path;
	// private Path origDir;

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> cast(WatchEvent<?> event) {
		return (WatchEvent<T>) event;
	}

	/**
	 * Register the given directory with the WatchService
	 */
	private void register(Path dir) throws IOException {

		WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
		if (trace) {
			Path prev = keys.get(key);
			if (prev == null) {
				System.out.format("register: %s\n", dir);
			} else {
				if (!dir.equals(prev)) {
					System.out.format("update: %s -> %s\n", prev, dir);
				}
			}
		}
		keys.put(key, dir);
	}

	/**
	 * Register the given directory, and all its sub-directories, with the
	 * WatchService.
	 */
	private void registerAll(final Path start) throws IOException {
		// register directory and sub-directories
		Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				register(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Creates a WatchService and registers the given directory
	 * 
	 * @param dir
	 * @param recursive
	 * @throws IOException
	 */
	public void initialize(Path dir, boolean recursive) throws IOException {

		// origDir = dir;

		this.watcher = FileSystems.getDefault().newWatchService();
		this.keys = new HashMap<WatchKey, Path>();
		this.recursive = recursive;

		if (recursive) {
			registerAll(dir);
		} else {
			register(dir);
		}

		// enable trace after initial registration
		this.trace = true;
	}

	/**
	 * Process all events for keys queued to the watcher
	 */
	void processEvents() {
		for (;;) {

			// wait for key to be signalled
			WatchKey key;
			try {
				key = watcher.take();
			} catch (InterruptedException x) {
				return;
			}

			Path dir = keys.get(key);
			if (dir == null) {
				System.err.println("WatchKey not recognized!!");
				continue;
			}

			for (WatchEvent<?> event : key.pollEvents()) {
				Kind<?> kind = event.kind();

				// TBD - provide example of how OVERFLOW event is handled
				if (kind == OVERFLOW) {
					continue;
				}

				// Context for directory entry event is the file name of entry
				WatchEvent<Path> ev = cast(event);
				Path name = ev.context();
				Path child = dir.resolve(name);

				// if directory is created, and watching recursively, then
				// register it and its sub-directories
				if (recursive && (kind == ENTRY_CREATE)) {
					try {
						if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
							registerAll(child);
						}
					} catch (IOException x) {
						x.printStackTrace();
					}
				}

				if ((kind == ENTRY_CREATE || kind == ENTRY_MODIFY)
						&& false == Files.isDirectory(child, NOFOLLOW_LINKS)) {

					String filename = child.getFileName().toString();

					if (filename.endsWith(".json")) {

						String json = null;
						try {
							json = new String(Files.readAllBytes(child));
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}

						updateManagedMBeans(child, json);

					}

				}

			}

			// reset key and remove from set if directory no longer accessible
			boolean valid = key.reset();
			if (!valid) {
				keys.remove(key);

				// all directories are inaccessible
				if (keys.isEmpty()) {
					break;
				}
			}
		}
	}

	/**
	 * This method attempts to determine if OCCAS is running on a shared filesystem.
	 * It does that by looking in the 'servers' directory and counting the number of
	 * directories found. If there are more than two directories (AdminServer and
	 * domain_bak), then it must be a shared filesystem.
	 * 
	 * @return if OCCAS is running on a shared filesystem
	 * @throws IOException
	 */
	public boolean isSharedFilesystem() throws IOException {
		boolean value = false;

		long count = Files.find(Paths.get("./servers/"), 1, // how deep do we want to descend
				(path, attributes) -> attributes.isDirectory()).count() - 1; // '-1' because '/tmp' is also counted in

		if (count > 2) {
			value = true;
		}

		System.out.println("Is this a shared filesystem? " + value);

		return value;
	}

	public void updateManagedMBeans(Path path, String json) {

		String filename = path.getFileName().toString();
		String appName = filename.substring(0, filename.indexOf(".json"));
		String parent = path.getParent().toFile().getName();
		String grandparent = path.getParent().getParent().toFile().getName();

		System.out.println("Path grandparent: " + grandparent);

		boolean cluster = grandparent.equals("_clusters");
		boolean server = grandparent.equals("_servers");
		boolean domain = !(server || cluster);

		System.out.println("domain=" + domain + ", cluster=" + cluster + ", server=" + server);

		try {
			InitialContext ctx = new InitialContext();
			MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			ObjectName objectName = null;
			if (domain) {
				objectName = new ObjectName("vorpal.blade:Name=" + appName + ",Type=Configuration,*");
			} else if (server) {
				objectName = new ObjectName(
						"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=" + parent + ",*");
			} else if (cluster) {
				objectName = new ObjectName(
						"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=*,Cluster=" + parent);
			}

			System.out.println("Configuration changed...");
			System.out.println("looking for " + objectName.toString());

			Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);

			for (ObjectInstance mbean : mbeans) {
				ObjectName name = mbean.getObjectName();
				System.out.println("Found... " + name.toString());

				SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);

				if (false == this.isSharedFilesystem()) {
					if (domain) {
						System.out.println("Updating Domain...");
//						settings.open(ConfigType.DOMAIN);
						settings.open("DOMAIN");
					} else if (cluster) {
						System.out.println("Updating Cluster...");
//						settings.open(ConfigType.CLUSTER);
						settings.open("CLUSTER");
					} else if (server) {
						System.out.println("Updating Server...");
//						settings.open(ConfigType.SERVER);
						settings.open("SERVER");
					}

					String line;
					int count = 0;
					Scanner scanner = new Scanner(json);
					while (scanner.hasNextLine()) {
						line = scanner.nextLine();
//						settings.write(line);
						count++;
					}
					System.out.println("Number of lines written: " + count);
					scanner.close();
//					settings.close();
				}

				System.out.print("Sending command to reload the configuration... ");
				settings.reload();
				System.out.println("Success!");

			}

			ctx.close();

		} catch (NameNotFoundException e) {
			System.out.println(e.getMessage());
			System.out.println(
					"Please verify that this application is running in the AdminServer (and not in a managed engine tier node).");
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static Set<String> queryApps() throws NamingException {
		Set<String> apps = new TreeSet<>();

		InitialContext ctx = new InitialContext();
		try {
			MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			ObjectName objectName = null;
//			objectName = new ObjectName("vorpal.blade:Name=*,Type=Configuration,*");
//			"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=" + parent + ",*");
//			"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=*,Cluster=" + parent);
			objectName = new ObjectName("vorpal.blade:Name=*,Type=Configuration,*");

			Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);

			for (ObjectInstance mbean : mbeans) {
				ObjectName name = mbean.getObjectName();
				System.out.println("Found... " + name.toString());
				
				apps.add( name.getKeyProperty("Name"));
				
//				apps.add(name.toString());
//				SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);

			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			ctx.close();
		}

		return apps;

	}

	@Override
	public void run() {
		processEvents();
	}

}
