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
import java.util.Set;
import java.util.TreeSet;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.vorpal.blade.framework.v2.config.SettingsMXBean;
import org.vorpal.blade.framework.v3.events.ConfigEvents;

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

	/// Explicit operator declaration of whether the config directory is shared
	/// with the engine tier — system property `blade.shared.filesystem` or env
	/// `BLADE_SHARED_FILESYSTEM` (true|false). When set, the timestamp heuristic
	/// is bypassed entirely. Declaring it removes the NFS attribute-cache race
	/// where a shared file briefly looks local and the engine then writes back
	/// over the file the admin just wrote (re-triggering this watch). Returns
	/// null when undeclared.
	static Boolean explicitSharedFileSystem() {
		String v = System.getProperty("blade.shared.filesystem");
		if (v == null || v.isEmpty()) {
			v = System.getenv("BLADE_SHARED_FILESYSTEM");
		}
		return (v == null || v.isEmpty()) ? null : Boolean.valueOf(v.trim());
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
		this.watchedDir = dir;
		this.startedAt = System.currentTimeMillis();
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

	private Path watchedDir;
	private long startedAt;

	/// Stop the watch loop and release the WatchService. A change still queued
	/// (or never reached) is not pushed now: every configuration file changed
	/// since the watcher started, at a version it never handled, is listed
	/// pending (ValidationAPI), so a Publish or the next switch-on sends it.
	public void shutdown() {
		this.interrupt();
		if (watcher != null) {
			try {
				watcher.close();
			} catch (IOException e) {
				// best effort
			}
		}
		// Let a push in progress finish or fail on its own; not when the watcher
		// itself is stopping it (its own push of blade-configurator.json reloads it).
		if (Thread.currentThread() != this) {
			try {
				this.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		listMissed();
	}

	void listMissed() {
		if (watchedDir == null) return;
		java.util.Set<Path> handled = new java.util.HashSet<>();
		java.util.Map<Path, String> versions = new java.util.HashMap<>();
		pushed.forEach((file, version) -> {
			Path key = file.toAbsolutePath().normalize();
			handled.add(key);
			versions.put(key, version);
		});
		java.util.List<Path> files = new java.util.ArrayList<>();
		try (java.util.stream.Stream<Path> top = Files.list(watchedDir)) {
			top.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
		} catch (IOException e) {
			return;
		}
		for (String scope : new String[] { "_clusters", "_servers" }) {
			Path dir = watchedDir.resolve(scope);
			if (!Files.isDirectory(dir)) continue;
			try (java.util.stream.Stream<Path> all = Files.walk(dir, 2)) {
				all.filter(p -> p.toString().endsWith(".json") && p.getParent().getParent().equals(dir)).forEach(files::add);
			} catch (IOException e) {
				// best effort
			}
		}
		for (Path f : files) {
			try {
				String name = f.getFileName().toString();
				String app = name.substring(0, name.length() - ".json".length());
				if ("blade-configurator".equals(app) || !ValidationAPI.appName(app)) continue;   // its own switch; not an app
				// To the second: a filesystem with whole-second times rounds a
				// file written in the watcher's first second below its start.
				if (Files.getLastModifiedTime(f).toMillis() < startedAt / 1000 * 1000) continue;
				Path key = f.toAbsolutePath().normalize();
				if (!FileManagerServlet.fileVersion(f).equals(versions.get(key))) ValidationAPI.watcherMissed(app);
			} catch (IOException e) {
				// gone meanwhile
			}
		}
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
//							json = new String(Files.readAllBytes(child), java.nio.charset.StandardCharsets.UTF_8);
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
							if (Thread.currentThread().isInterrupted()) break;   // stopping: shutdown lists the rest
							try {

								Kind<?> kind = event.kind();

								// Events were lost: what changed is not known, so every
								// file not pushed as it now is goes pending.
								if (kind == OVERFLOW) {
									if (inTemplates(dir)) ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);
									else listMissed();
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
											// What was already written into it before it was
											// watched (a new server folder copied in at once).
											try (java.util.stream.Stream<Path> inside = Files.walk(child)) {
												for (Path f : (Iterable<Path>) inside.filter(Files::isRegularFile)::iterator) {
													if (!considerFile(f)) break;
												}
											}
										}
									} catch (IOException x) {
										x.printStackTrace();
									}
								}

								// A new file counts as much as a changed one: a config
								// created for an application that ran on its sample is
								// the first one it has ever had.
								if ((kind == ENTRY_MODIFY || kind == ENTRY_CREATE) //
										&& false == Files.isDirectory(child, NOFOLLOW_LINKS)) {
									if (!considerFile(child)) continue;
								}

								// A deleted configuration stays on engines with their own
								// disk until a Publish clears it there: pending.
								// Back already (an editor that renames the original aside,
								// then writes it): the CREATE pushes it.
								if (kind == ENTRY_DELETE && Files.exists(child)) continue;
								// A cluster or server folder deleted, renamed or moved: its
								// overlays stay on those servers until a Publish writes them
								// empty there. Which apps it held is gone with it: every
								// app with a domain file is pending.
								if (kind == ENTRY_DELETE && isPlaceRoot(dir)) {
									try (java.util.stream.Stream<Path> top = Files.list(watchedDir)) {
										top.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
												.map(n -> n.substring(0, n.length() - ".json".length()))
												.filter(a -> ValidationAPI.appName(a) && !"blade-configurator".equals(a))
												.forEach(a -> ValidationAPI.watcherMissed(a, true));
									}
									continue;
								}
								if (kind == ENTRY_DELETE && child.getFileName().toString().endsWith(".json")
										&& isConfigPlace(child)) {
									String gone = child.getFileName().toString();
									String app = gone.substring(0, gone.length() - ".json".length());
									if (ValidationAPI.appName(app) && !"blade-configurator".equals(app)) {
										ValidationAPI.watcherMissed(app, !child.toAbsolutePath().normalize().getParent()
												.equals(watchedDir.toAbsolutePath().normalize()));
									}
								}
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

	/// Versions already pushed, by file. One save arrives as two events (the
	/// truncate and the write, or a create and a modify), and each used to push
	/// the file to every server and reload it, twice. The version includes a
	/// checksum: on a filesystem with whole-second times two saves in one
	/// second share a modification time, and the second was never pushed.
	private final java.util.Map<Path, String> pushed = new java.util.concurrent.ConcurrentHashMap<>();

	/// True the first time a file is seen with its current content.
	boolean firstSightOf(Path file) {
		String version = FileManagerServlet.fileVersion(file);
		if ("none".equals(version) || "unknown".equals(version)) return false;
		return !version.equals(pushed.put(file, version));
	}

	/// Pushes a changed template to every server; never throws (the watch
	/// thread must keep running).
	private void pushTemplate(Path file) {
		// Both by real path: the watcher's file comes from the working
		// directory (physical), the base from DOMAIN_HOME (maybe a link).
		String path = real(TemplatePush.base()).relativize(real(file)).toString().replace('\\', '/');
		try {
			MBeanServer mbs = (MBeanServer) new InitialContext().lookup("java:comp/env/jmx/domainRuntime");
			TemplatePush.Result r = TemplatePush.push(mbs, path);
			System.out.println("ConfigurationMonitor template " + path + ": " + r.summary());
			if (!r.failed.isEmpty()) ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);   // Publish retries every template
		} catch (Exception e) {
			System.out.println("ConfigurationMonitor template " + path + " not pushed: " + e.getMessage());
			ValidationAPI.watcherMissed(ValidationAPI.TEMPLATES);
		}
	}

	/// A domain, cluster or server configuration file's place: the watched
	/// folder itself, or a folder in its `_clusters` or `_servers`.
	private boolean isConfigPlace(Path p) {
		if (watchedDir == null) return false;
		Path root = watchedDir.toAbsolutePath().normalize();
		Path folder = p.toAbsolutePath().normalize().getParent();
		return folder.equals(root) || (folder.getParent() != null
				&& (folder.getParent().equals(root.resolve("_clusters")) || folder.getParent().equals(root.resolve("_servers"))));
	}

	/// `_clusters` or `_servers` itself: its entries are cluster or server folders.
	private boolean isPlaceRoot(Path d) {
		if (watchedDir == null) return false;
		Path root = watchedDir.toAbsolutePath().normalize();
		Path f = d.toAbsolutePath().normalize();
		return f.equals(root.resolve("_clusters")) || f.equals(root.resolve("_servers"));
	}

	/// Under `_templates/`, compared by real path (DOMAIN_HOME may be spelled
	/// through a link while the watcher runs on the resolved one).
	private static boolean inTemplates(Path p) {
		return real(p).startsWith(real(TemplatePush.base()));
	}

	private static Path real(Path p) {
		try {
			return p.toRealPath();
		} catch (IOException gone) {
			Path parent = p.toAbsolutePath().normalize().getParent();
			try {
				return parent == null ? p.toAbsolutePath().normalize() : parent.toRealPath().resolve(p.getFileName());
			} catch (IOException e) {
				return p.toAbsolutePath().normalize();
			}
		}
	}

	/// One changed (or new) file: a template pushed to every server; a
	/// configuration pushed once its writer has finished. A name that is not an
	/// application's ("*", "?") is skipped: in the MBean query it would be a
	/// wildcard matching every application. False when stopped while waiting.
	private boolean considerFile(Path child) {
		String filename = child.getFileName().toString();
		if (inTemplates(child)) {
			if (TemplatePush.isTemplate(child) && firstSightOf(child)) pushTemplate(child);
			return true;
		}
		if (!filename.endsWith(".json")) return true;   // temp files, .versions backups
		String app = filename.substring(0, filename.length() - ".json".length());
		if (!ValidationAPI.appName(app) || !isConfigPlace(child)) return true;   // before the second's wait
		if (!firstSightOf(child)) return true;
		try {
			Thread.sleep(1000); // let the writer finish before reading
		} catch (InterruptedException stopped) {
			// Auto-publish switched off while this waited: the change is not
			// pushed, so it is pending, not silently live (not the Configurator's
			// own file: its write is what stopped the watcher). The rest is left
			// to shutdown's listing.
			if (!"blade-configurator".equals(app)) ValidationAPI.watcherMissed(app);
			Thread.currentThread().interrupt();
			return false;
		}
		String json = null;
		try {
			json = new String(Files.readAllBytes(child), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.out.println("ConfigurationMonitor unable to read " + child.toAbsolutePath());
			return true;
		}
		updateManagedMBeans(child, json);
		return true;
	}

	public void updateManagedMBeans(Path path, String json) {
		String file = path.getFileName().toString();
		String app = file.contains(".json") ? file.substring(0, file.indexOf(".json")) : file;
		// One push per app at a time, shared with Publish (ValidationAPI.pushLock).
		synchronized (ValidationAPI.pushLock(app)) {
			updateManagedMBeansLocked(path, json);
		}
	}

	private void updateManagedMBeansLocked(Path path, String json) {
		System.out.println("Begin... ConfigurationMonitor.updateManagedMBeans path=" + path);

		boolean isSharedFileSystem = true;

		String filename = path.getFileName().toString();
		String appName = filename.substring(0, filename.indexOf(".json"));
		String parent = path.getParent().toFile().getName();

		// By place, not by folder name: a server or cluster called "vorpal"
		// would otherwise read as the domain file, and its overlay be pushed as
		// every engine's domain file.
		Path root = (watchedDir != null ? watchedDir : path.getParent()).toAbsolutePath().normalize();
		Path folder = path.toAbsolutePath().normalize().getParent();
		boolean domain = folder.equals(root);
		boolean cluster = !domain && folder.getParent() != null && folder.getParent().equals(root.resolve("_clusters"));
		boolean server = !domain && folder.getParent() != null && folder.getParent().equals(root.resolve("_servers"));

		System.out.println("ConfigurationMonitor.updateManagedMBeans domain=" + domain + ", cluster=" + cluster
				+ ", server=" + server);

		InitialContext ctx = null;
		ObjectName objectName = null;
		MBeanServer mbeanServer = null;
		java.util.List<String> pushedTo = new java.util.ArrayList<>();
		java.util.List<String> failed = new java.util.ArrayList<>();
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
							"vorpal.blade:Name=" + appName + ",Type=Configuration,Cluster=" + parent + ",*");
				}

				System.out.println("ConfigurationMonitor.updateManagedMBeans looking for " + objectName);

				if (mbeanServer != null && objectName != null) {

					Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);
					for (ObjectInstance mbean : mbeans) {
						// Isolate each MBean. A failure on one server — e.g. an
						// AdminServer-local Configuration MBean denying the monitor
						// thread's unauthenticated JMX call (NoAccessRuntimeException) —
						// must not abort distribution to the other servers, and (before
						// this) escaped far enough to kill the whole watch thread, which
						// stopped all config distribution until a redeploy.
						try {
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

						Boolean declared = explicitSharedFileSystem();
						if (declared != null) {
							isSharedFileSystem = declared.booleanValue();
						} else {
							// EXACT match: one physical file reports one mtime, so the
							// admin's copy and the engine's copy read identical only when
							// they ARE the same file (a shared mount). A tolerance window
							// (commit 840f86cf) regressed this — it read a remote engine
							// whose file had just been pushed (mtime ~1s off) as shared, so
							// that engine skipped the copy and reloaded its stale local
							// config. If a shared mount ever reports mtime at coarser
							// granularity than the admin, declare it with
							// blade.shared.filesystem rather than widening this compare.
							isSharedFileSystem = (localTimestamp == remoteTimestamp);
						}

						System.out.println("ConfigurationMonitor.updateManagedMBeans localTimestamp=" + localTimestamp
								+ ", remoteTimestamp=" + remoteTimestamp + ", isSharedFileSystem=" + isSharedFileSystem
								+ (declared != null ? " (declared)" : ""));

						if (false == isSharedFileSystem) {
							// Written, then read back (ValidationAPI.writeAndVerify): an engine
							// that could not write its file fails here, before its reload.
							String type = domain ? "DOMAIN" : cluster ? "CLUSTER" : "SERVER";
							ValidationAPI.writeAndVerify(settings, type, json);
							System.out.println("ConfigurationMonitor.updateManagedMBeans pushed and verified " + type);
						}

						System.out.println("ConfigurationMonitor.updateManagedMBeans invoking reload()");
						settings.reload();
						pushedTo.add(String.valueOf(name.getKeyProperty("Location")));
						} catch (Exception mbeanEx) {
							System.out.println("ConfigurationMonitor.updateManagedMBeans MBean skipped ("
									+ mbeanEx.getClass().getSimpleName() + "): " + mbeanEx.getMessage());
							failed.add(String.valueOf(mbean.getObjectName().getKeyProperty("Location")));
						}
					}
				}

				// On every server: no longer pending. Failed somewhere: pending,
				// so every page offers Publish for it.
				// Only a domain file reaches every server running the app: a cluster
				// or server file pushed fine says nothing of an earlier failure
				// elsewhere.
				if (!failed.isEmpty()) ValidationAPI.watcherMissed(appName, !domain);
				else if (domain) ValidationAPI.domainPushed(appName);

				// Every change seen, whoever made it: an editor, a restore, or a
				// hand edit on this server that no editor could have recorded.
				String relative = domain ? filename : (cluster ? "_clusters/" : "_servers/") + parent + "/" + filename;
				ConfigEvents.published(relative, appName, domain ? "domain" : (cluster ? "cluster" : "server"),
						pushedTo, failed);

			} catch (NameNotFoundException e) {
				System.out.println(e.getMessage());
				System.out.println(
						"Please verify that the BLADE console application is running in the AdminServer and not in a managed engine tier node.");
				ValidationAPI.watcherMissed(appName, !domain);   // reached no engine: pending, not silently live
			} catch (Exception e) {
				e.printStackTrace();
				ValidationAPI.watcherMissed(appName, !domain);
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

				apps.add(name.getKeyProperty("Name"));

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
