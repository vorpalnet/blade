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
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;

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
				WatchEvent.Kind kind = event.kind();

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

				if ((kind == ENTRY_CREATE || kind == ENTRY_MODIFY) && false == Files.isDirectory(child, NOFOLLOW_LINKS)) {

					String filename = child.getFileName().toString();
					if (filename.endsWith(".json")) {

						String appName = filename.substring(0, filename.indexOf(".json"));

						String json = null;
						try {
							json = new String(Files.readAllBytes(child));
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}

						updateManagedMBeans(appName, json);

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

	public void updateManagedMBeans(String appName, String json) {

		try {
			InitialContext ctx = new InitialContext();
			MBeanServer server = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

			ObjectName objectName = new ObjectName("vorpal.blade:Name=" + appName + ",Type=Configuration");
			System.out.println("Configuration changed...");
			System.out.println("looking for " + objectName.toString());

			Set<ObjectInstance> mbeans = server.queryMBeans(objectName, null);

			for (ObjectInstance mbean : mbeans) {
				ObjectName name = mbean.getObjectName();

				System.out.println("Getting Settings MBean...");
				SettingsMXBean settings = JMX.newMXBeanProxy(server, name, SettingsMXBean.class);

				System.out.println("Updating JSON...");
				settings.setJson(json);

//				System.out.println("Updating MBean: " + name);
//
//				UpdateAction action = new UpdateAction(server, name, new Attribute("Data", json));
//				System.out.println("UpdateAction: " + action);
//
//				AccessControlContext acc = AccessController.getContext();
//				System.out.println("AccessControlContext: " + acc);
//
//				Subject subject = Subject.getSubject(acc);
//				System.out.println("Subject: " + subject);
//
//				Object obj = weblogic.security.Security.runAs(subject, action);
//				System.out.println("weblogic.security.Security: " + obj);
//
//				server.setAttribute(name, new Attribute("Data", json) );

			}

			ctx.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void run() {
		processEvents();
	}

}
