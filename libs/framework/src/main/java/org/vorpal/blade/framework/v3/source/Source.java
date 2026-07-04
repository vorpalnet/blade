package org.vorpal.blade.framework.v3.source;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import javax.servlet.ServletContext;

/// [SourceMXBean] implementation — the per-app source inventory.
///
/// Scans two places, both inside this app's own WAR:
///
/// 1. `/WEB-INF/classes/**/*.java` — the app's source, bundled by the parent
///    pom's `.java` resource include.
/// 2. `WEB-INF/lib/vorpal-blade-library-framework*.jar` `**/*.java` entries —
///    the framework's source, bundled the same way inside the framework JAR
///    (framework callflows like `InitialInvite` run as part of this app, so
///    they belong in its inventory).
///
/// Each `.java` is paired with its class (loaded WITHOUT initialization, so the
/// probe has no side effects) to mark whether it is a `Callflow` — the flag the
/// Callflow Viewer's gallery filters on. The scan is lazy and cached: nothing
/// happens at deploy time, and the first JMX read pays the walk once. There is
/// no invalidation because WAR content can't change without a redeploy, which
/// re-registers a fresh instance.
public class Source implements SourceMXBean {

	private static final String CLASSES_PREFIX = "/WEB-INF/classes/";
	private static final String LIB_PREFIX = "/WEB-INF/lib/";
	private static final String FRAMEWORK_JAR_PREFIX = "vorpal-blade-library-framework";

	private final ServletContext servletContext;
	private final String appName;

	/// className → classpath resource path of its `.java`. The manifest and the
	/// whitelist [#getSource] checks against. Built once, on first use.
	private volatile Map<String, String> sources;

	/// The subset of scanned classes whose `.java` came from `WEB-INF/classes`
	/// — the app's OWN code, as opposed to the framework JAR's. Feeds the
	/// manifest's `used` flag. Built by the same scan.
	private volatile java.util.Set<String> ownClasses;

	public Source(ServletContext servletContext, String appName) {
		this.servletContext = servletContext;
		this.appName = appName;
	}

	@Override
	public String getManifestJson() {
		Map<String, String> map = scan();
		StringBuilder json = new StringBuilder();
		json.append("{\"app\":").append(quote(appName)).append(",\"sources\":[");
		boolean first = true;
		for (Map.Entry<String, String> e : map.entrySet()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append("{\"className\":").append(quote(e.getKey()))
					.append(",\"callflow\":").append(isCallflow(e.getKey()))
					.append(",\"used\":").append(isUsed(e.getKey())).append('}');
		}
		json.append("]}");
		return json.toString();
	}

	/// Does this app IMPLEMENT this class, in the gallery's sense? True for the
	/// app's own code, and for bundled framework classes the app has actually
	/// instantiated ([CallflowRegistry] — populated live by the v3 `Callflow`
	/// constructor, so this flag tracks traffic; only the path map is cached).
	private boolean isUsed(String className) {
		java.util.Set<String> own = ownClasses;
		return (own != null && own.contains(className)) || CallflowRegistry.contains(className);
	}

	@Override
	public String getSource(String className) {
		if (className == null) {
			return null;
		}
		String path = scan().get(className);
		if (path == null) {
			return null;
		}
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
			return in != null ? readAll(in) : null;
		} catch (Exception e) {
			return null;
		}
	}

	// ================================================================== scan

	private Map<String, String> scan() {
		Map<String, String> map = sources;
		if (map == null) {
			synchronized (this) {
				map = sources;
				if (map == null) {
					map = new TreeMap<>();
					try {
						scanClassesDir(map, CLASSES_PREFIX);
						ownClasses = new java.util.HashSet<>(map.keySet());
						scanFrameworkJar(map);
					} catch (Exception ignore) {
						// inventory is best-effort; serve whatever was found
					}
					if (ownClasses == null) {
						ownClasses = new java.util.HashSet<>();
					}
					sources = map = new LinkedHashMap<>(map);
				}
			}
		}
		return map;
	}

	/// Recursive walk of `/WEB-INF/classes/` via the servlet context. Paths
	/// ending in `/` are directories; recurse. `.java` files map to a class
	/// name by stripping the prefix and suffix.
	private void scanClassesDir(Map<String, String> map, String dir) {
		Set<String> paths = servletContext.getResourcePaths(dir);
		if (paths == null) {
			return;
		}
		for (String path : paths) {
			if (path.endsWith("/")) {
				scanClassesDir(map, path);
			} else if (path.endsWith(".java")) {
				String resource = path.substring(CLASSES_PREFIX.length());
				map.put(toClassName(resource), resource);
			}
		}
	}

	/// Lists the `.java` entries inside the bundled framework JAR. Only the
	/// framework JAR is opened — by the skinny-WAR convention it is the only
	/// JAR in `WEB-INF/lib` — and only entry NAMES are read here; content
	/// loads lazily through the classloader in [#getSource].
	private void scanFrameworkJar(Map<String, String> map) {
		Set<String> libs = servletContext.getResourcePaths(LIB_PREFIX);
		if (libs == null) {
			return;
		}
		for (String lib : libs) {
			String file = lib.substring(lib.lastIndexOf('/') + 1);
			if (!file.startsWith(FRAMEWORK_JAR_PREFIX) || !file.endsWith(".jar")) {
				continue;
			}
			try (JarInputStream jar = new JarInputStream(servletContext.getResourceAsStream(lib))) {
				for (JarEntry entry; (entry = jar.getNextJarEntry()) != null;) {
					String name = entry.getName();
					if (!entry.isDirectory() && name.endsWith(".java")) {
						map.put(toClassName(name), name);
					}
				}
			} catch (Exception ignore) {
				// framework source unavailable; app source still serves
			}
		}
	}

	private static String toClassName(String resource) {
		return resource.substring(0, resource.length() - ".java".length()).replace('/', '.');
	}

	/// Is this class a BLADE callflow? Loaded with `initialize=false` so the
	/// probe can't run static initializers; anything unloadable (package-info,
	/// classes with unsatisfied optional deps) is simply not a callflow. The
	/// base classes themselves are excluded — the gallery wants the flows, and
	/// the v2/v3 `Callflow` bases are framework plumbing.
	private boolean isCallflow(String className) {
		try {
			Class<?> c = Class.forName(className, false, getClass().getClassLoader());
			Class<?> base = org.vorpal.blade.framework.v2.callflow.Callflow.class;
			return base.isAssignableFrom(c)
					&& !c.equals(base)
					&& !c.equals(org.vorpal.blade.framework.v3.Callflow.class);
		} catch (Throwable t) {
			return false;
		}
	}

	// ================================================================== util

	private static String readAll(InputStream in) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		for (int n; (n = in.read(buf)) != -1;) {
			out.write(buf, 0, n);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static String quote(String s) {
		StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < (s == null ? 0 : s.length()); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"':
				out.append("\\\"");
				break;
			case '\\':
				out.append("\\\\");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\t':
				out.append("\\t");
				break;
			default:
				if (c < 0x20) {
					out.append(String.format("\\u%04x", (int) c));
				} else {
					out.append(c);
				}
			}
		}
		return out.append('"').toString();
	}
}
