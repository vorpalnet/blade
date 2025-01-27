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

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.vorpal.blade.framework.v2.config.SettingsMXBean;

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

		// create sub-directories if they do not exist
		String configPath = "./config/custom/vorpal/";
		Path domainPath = Paths.get(configPath);
		Files.createDirectories(domainPath);

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
		System.out.println("BLADE Console, ConfigurationMonitor dir=" + dir + ", recursive=" + recursive);

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

//	/**
//	 * Process all events for keys queued to the watcher
//	 */
//	void processEvents() {
//		for (;;) {
//
//			// wait for key to be signalled
//			WatchKey key;
//			try {
//				key = watcher.take();
//			} catch (InterruptedException x) {
//				return;
//			}
//
//			Path dir = keys.get(key);
//			if (dir == null) {
//				System.err.println("WatchKey not recognized!!");
//				continue;
//			}
//
//			for (WatchEvent<?> event : key.pollEvents()) {
//
//				Kind<?> kind = event.kind();
//
//				// TBD - provide example of how OVERFLOW event is handled
//				if (kind == OVERFLOW) {
//					continue;
//				}
//
//				// Context for directory entry event is the file name of entry
//				WatchEvent<Path> ev = cast(event);
//				Path name = ev.context();
//				Path child = dir.resolve(name);
//
//				System.out.println("ConfigurationMonitor WatchEvent event.kind.name=" + event.kind().name() + ", child="
//						+ child.getFileName());
//
//				// if directory is created, and watching recursively, then
//				// register it and its sub-directories
//				if (recursive && (kind == ENTRY_CREATE)) {
//					try {
//						if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
//							registerAll(child);
//						}
//					} catch (IOException x) {
//						x.printStackTrace();
//					}
//				}
//
//				if ((kind == ENTRY_CREATE || kind == ENTRY_MODIFY)
//						&& false == Files.isDirectory(child, NOFOLLOW_LINKS)) {
//
//					String filename = child.getFileName().toString();
//
//					if (filename.endsWith(".json")) {
//
//						String json = null;
//						try {
//							json = new String(Files.readAllBytes(child));
//						} catch (IOException e) {
//							e.printStackTrace();
//						}
//
//						updateManagedMBeans(child, json);
//
//					}
//
//				}
//
//			}
//
//			// reset key and remove from set if directory no longer accessible
//			boolean valid = key.reset();
//			if (!valid) {
//				keys.remove(key);
//
//				// all directories are inaccessible
//				if (keys.isEmpty()) {
//					break;
//				}
//			}
//		}
//	}

	/**
	 * Process all events for keys queued to the watcher
	 */
	private void processEvents() {
		System.out.println("ConfigurationMonitor start processing events...");

		WatchKey key;
		Path dir;
		boolean valid;

		try {

			// wait for key to be signaled
			while ((key = watcher.take()) != null) {

				try {

					dir = keys.get(key);
					if (null != dir) {

						WatchEvent<Path> ev;
						Path name;
						Path child;

						for (WatchEvent<?> event : key.pollEvents()) {
							try {

								Kind<?> kind = event.kind();

								// TBD - provide example of how OVERFLOW event is handled
								if (kind == OVERFLOW) {
									continue;
								}

								// Context for directory entry event is the file name of entry
								ev = cast(event);
								name = ev.context();
								child = dir.resolve(name);

//								System.out.println("ConfigurationMonitor WatchEvent event.kind.name="
//										+ event.kind().name() + ", child=" + child.getFileName());

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

//							if ((kind == ENTRY_CREATE || kind == ENTRY_MODIFY)
//									&& false == Files.isDirectory(child, NOFOLLOW_LINKS)) {
								if (kind == ENTRY_MODIFY //
										&& false == Files.isDirectory(child, NOFOLLOW_LINKS)) {

//									System.out.println("ConfigurationMonitor sleeping for 500");
									Thread.sleep(1000); // prevent processing multiple MODIFY events on the same file

									String filename = child.getFileName().toString();

									if (filename.endsWith(".json")) {

										String json = null;
										try {
											json = new String(Files.readAllBytes(child));
										} catch (IOException e) {
											System.out.println("ConfigurationMonitor unable to read "
													+ child.getFileName().toAbsolutePath());

											e.printStackTrace();
										}

										updateManagedMBeans(child, json);

									} // if filename

								} // if kind
							} catch (Exception forEx) {
								System.out.println("ConfigurationMonitor For Loop ERROR: " + forEx.getMessage());
							}

						} // for

					} // if dir

				} catch (Exception whileEx) {
					System.out.println("ConfigurationMonitor While Loop ERROR: " + whileEx.getMessage());
				} finally {

					// reset key and remove from set if directory no longer accessible
					if (key != null) {
						valid = key.reset();

						if (false == valid) {
							keys.remove(key);
							// all directories are inaccessible
							if (keys.isEmpty()) {
								System.out.println(
										"ConfigurationMonitor FATAL ./config/custom/vorpal directory has been deleted!");
								break;
							}
						}
					}
				}

			} // while

		} catch (Exception ex1) {
			System.out.println("ConfigurationMonitor processEvents ERROR " + ex1.getMessage());
		}

		System.out.println("ConfigurationMonitor stop processing events.");

	}

	public void updateManagedMBeans(Path path, String json) {
		System.out.println("Begin... ConfigurationMonitor.updateManagedMBeans path=" + path);

		boolean isSharedFileSystem = true;

		String filename = path.getFileName().toString();
		String appName = filename.substring(0, filename.indexOf(".json"));
		String parent = path.getParent().toFile().getName();
		String grandparent = path.getParent().getParent().toFile().getName();

		boolean domain = parent.equals("vorpal");
		boolean cluster = grandparent.equals("_clusters");
		boolean server = grandparent.equals("_servers");

		System.out.println("ConfigurationMonitor.updateManagedMBeans domain=" + domain + ", cluster=" + cluster
				+ ", server=" + server);

		InitialContext ctx = null;
		ObjectName objectName = null;
		MBeanServer mbeanServer = null;
		if (domain || cluster || server) {

			try {
				ctx = new InitialContext();
				mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");

				if (domain) {
					objectName = new ObjectName("vorpal.blade:Name=" + appName + ",Type=Configuration,*");
				} else if (server) {
					objectName = new ObjectName(
							"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=" + parent + ",*");
				} else if (cluster) {
					objectName = new ObjectName(
							"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=*,Cluster=" + parent);
				}

				System.out.println("ConfigurationMonitor.updateManagedMBeans looking for " + objectName);

				if (mbeanServer != null && objectName != null) {

					Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);
					for (ObjectInstance mbean : mbeans) {
						ObjectName name = mbean.getObjectName();
						System.out.println("ConfigurationMonitor.updateManagedMBeans found " + name);

						SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);

						long localTimestamp = 0;
						long remoteTimestamp = 0;

						localTimestamp = Files.readAttributes(path, BasicFileAttributes.class).lastModifiedTime()
								.toMillis();

						if (domain) {
							remoteTimestamp = settings.getLastModified("DOMAIN");
						} else if (cluster) {
							remoteTimestamp = settings.getLastModified("CLUSTER");
						} else if (server) {
							remoteTimestamp = settings.getLastModified("SERVER");
						}

						isSharedFileSystem = (localTimestamp == remoteTimestamp) ? true : false;

						System.out.println("ConfigurationMonitor.updateManagedMBeans localTimestamp=" + localTimestamp
								+ ", remoteTimestamp=" + remoteTimestamp + ", isSharedFileSystem="
								+ isSharedFileSystem);

						if (false == isSharedFileSystem) {
							System.out.println("ConfigurationMonitor.updateManagedMBeans openForWrite...");

							if (domain) {
								settings.openForWrite("DOMAIN");
							} else if (cluster) {
								settings.openForWrite("CLUSTER");
							} else if (server) {
								settings.openForWrite("SERVER");
							}

							String line;
							int count = 0;
							Scanner scanner = new Scanner(json);
							while (scanner.hasNextLine()) {
								line = scanner.nextLine();
								settings.write(line);
								count++;
							}
							System.out.println("ConfigurationMonitor.updateManagedMBeans lines written: " + count);
							scanner.close();
							settings.close();
						}

						System.out.println("ConfigurationMonitor.updateManagedMBeans invoking reload()");
						settings.reload();
					}
				}

			} catch (NameNotFoundException e) {
				System.out.println(e.getMessage());
				System.out.println(
						"Please verify that the BLADE console application is running in the AdminServer and not in a managed engine tier node.");
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				if (ctx != null) {
					try {
						ctx.close();
					} catch (NamingException e) {
						// who cares?
					}
				}
			}
		}

		System.out.println("End... ConfigurationMonitor.updateManagedMBeans path=" + path);
	}

//	public static Set<String> queryApps() throws NamingException {
//		Set<String> apps = new TreeSet<>();
//
//		InitialContext ctx = new InitialContext();
//		try {
//			MBeanServer mbeanServer = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
//
//			ObjectName objectName = null;
////			objectName = new ObjectName("vorpal.blade:Name=*,Type=Configuration,*");
////			"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=" + parent + ",*");
////			"vorpal.blade:Name=" + appName + ",Type=Configuration,Location=*,Cluster=" + parent);
//			objectName = new ObjectName("vorpal.blade:Name=*,Type=Configuration,*");
//
//			Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);
//
//			for (ObjectInstance mbean : mbeans) {
//				ObjectName name = mbean.getObjectName();
//				System.out.println("Found... " + name.toString());
//
//				apps.add(name.getKeyProperty("Name"));
//
////				apps.add(name.toString());
////				SettingsMXBean settings = JMX.newMXBeanProxy(mbeanServer, name, SettingsMXBean.class);
//
//			}
//		} catch (Exception e) {
//			e.printStackTrace();
//		} finally {
//			ctx.close();
//		}
//
//		return apps;
//
//	}

	@Override
	public void run() {
		processEvents();
	}

}
