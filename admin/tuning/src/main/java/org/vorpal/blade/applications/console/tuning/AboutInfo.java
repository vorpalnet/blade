package org.vorpal.blade.applications.console.tuning;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Read-only "About this deployment" readout for the top of the Tuning dashboard.
 * Pulls together the three things an operator wants to confirm at a glance:
 *
 * <ul>
 * <li><b>BLADE</b> — version / build date / license / copyright, read straight
 *     from the bundled framework JAR's MANIFEST.MF (the {@code WebLogic-Application-Version}
 *     attribute the parent pom stamps as {@code <revision>-<build.number>}).</li>
 * <li><b>Platform</b> — OCCAS and WebLogic (and Coherence) product versions, parsed
 *     from the Oracle home install inventory ({@code inventory/ContentsXML/comps.xml}),
 *     plus the live domain name / config version / JVM / OS from JMX + system props.</li>
 * <li><b>Patches</b> — the applied one-off patches, read from the OPatch inventory
 *     ({@code inventory/patches/*.xml}: each file's {@code patch-id} + {@code description}).</li>
 * </ul>
 *
 * (The deployed-app inventory lives in the portal launcher, not here.)
 *
 * Everything degrades gracefully: any source that can't be read (manifest absent,
 * inventory unreadable on a node that isn't the product-install host, a JMX hiccup)
 * yields an empty value rather than failing the whole panel. The Oracle home is the
 * AdminServer's local product install — {@code platform.home}'s parent — so the
 * inventory reads need no federation.
 */
@Path("/about")
@Tag(name = "About", description = "BLADE / OCCAS / WebLogic versions and applied patches")
public class AboutInfo {

	private static final ObjectMapper mapper = new ObjectMapper();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "BLADE version, OCCAS/WebLogic versions, and applied patches")
	public Response get() {
		ObjectNode out = mapper.createObjectNode();
		out.set("blade", bladeInfo());

		ObjectNode platform = out.putObject("platform");
		File oracleHome = oracleHome();
		if (oracleHome != null) {
			// Canonicalize for display: MW_HOME is typically exported by the WLS
			// env scripts as the literal "$WL_HOME/.." and getAbsolutePath()
			// would pass the ".." straight through to the UI.
			String homePath;
			try {
				homePath = oracleHome.getCanonicalPath();
			} catch (java.io.IOException e) {
				homePath = oracleHome.getAbsolutePath();
			}
			platform.put("oracleHome", homePath);
			readComps(oracleHome, platform);
		}
		// JVM + OS from this (Admin) JVM — same product install everywhere in a cluster.
		platform.put("java", System.getProperty("java.version", "")
				+ vendorSuffix(System.getProperty("java.vendor")));
		platform.put("os", System.getProperty("os.name", "") + " "
				+ System.getProperty("os.version", "") + " (" + System.getProperty("os.arch", "") + ")");

		ArrayNode patches = oracleHome != null ? readPatches(oracleHome) : mapper.createArrayNode();
		out.set("patches", patches);

		// Live domain identity via the federated DomainRuntime.
		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			ObjectName drs = new ObjectName(
					"com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
			ObjectName domainConfig = (ObjectName) mbs.getAttribute(drs, "DomainConfiguration");
			platform.put("domain", str(mbs, domainConfig, "Name"));
			platform.put("configVersion", str(mbs, domainConfig, "ConfigurationVersion"));
		} catch (Exception ignore) {
		}

		return Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	// ---- BLADE: framework JAR manifest -------------------------------------

	/**
	 * Reads the bundled framework JAR's MANIFEST.MF by anchoring on a framework
	 * class and opening the very JAR it loaded from — so it's the manifest of the
	 * <i>actual</i> framework in play, whether that's WEB-INF/lib (the skinny-WAR
	 * norm) or the shared library.
	 */
	private ObjectNode bladeInfo() {
		ObjectNode b = mapper.createObjectNode();
		Manifest mf = frameworkManifest();
		Attributes a = mf != null ? mf.getMainAttributes() : new Attributes();
		b.put("version", nz(a.getValue("WebLogic-Application-Version")));
		b.put("built", nz(a.getValue("Implementation-Build-Date")));
		b.put("license", nz(a.getValue("License")));
		b.put("copyright", nz(a.getValue("Copyright")));
		return b;
	}

	/**
	 * Finds the framework JAR's manifest without depending on the class-resource URL
	 * protocol — WebLogic's filtering classloader hands back {@code zip:}/{@code wsjar:}
	 * URLs, not {@code jar:}, so the obvious {@code JarURLConnection} path silently reads
	 * nothing. Both routes here are protocol-agnostic:
	 *
	 * <ol>
	 * <li>The {@link java.security.CodeSource} of the framework class — its location is the
	 *     JAR (or exploded classes dir) the class actually loaded from. Open it as a JarFile,
	 *     or read a sibling {@code META-INF/MANIFEST.MF} if it's a directory.</li>
	 * <li>Failing that, scan every {@code META-INF/MANIFEST.MF} visible to the framework
	 *     classloader and take the first carrying the BLADE build stamp. The Tuning WAR's own
	 *     manifest carries the same stamp (same build), so this still yields correct values.</li>
	 * </ol>
	 */
	private Manifest frameworkManifest() {
		Class<?> anchor = SettingsManager.class;
		try {
			java.security.CodeSource cs = anchor.getProtectionDomain().getCodeSource();
			if (cs != null && cs.getLocation() != null && "file".equals(cs.getLocation().getProtocol())) {
				File f = new File(cs.getLocation().toURI());
				if (f.isFile()) {
					try (java.util.jar.JarFile jf = new java.util.jar.JarFile(f)) {
						Manifest m = jf.getManifest();
						if (m != null) return m;
					}
				} else if (f.isDirectory()) {
					File mfFile = new File(f, "META-INF/MANIFEST.MF");
					if (mfFile.isFile()) {
						try (java.io.InputStream in = new java.io.FileInputStream(mfFile)) {
							return new Manifest(in);
						}
					}
				}
			}
		} catch (Exception ignore) {
		}
		try {
			java.util.Enumeration<URL> e = anchor.getClassLoader().getResources("META-INF/MANIFEST.MF");
			while (e.hasMoreElements()) {
				try (java.io.InputStream in = e.nextElement().openStream()) {
					Manifest m = new Manifest(in);
					String v = m.getMainAttributes().getValue("WebLogic-Application-Version");
					if (v != null && !v.isEmpty()) return m;
				} catch (Exception ignore) {
				}
			}
		} catch (Exception ignore) {
		}
		return null;
	}

	// ---- Platform: OPatch inventory ----------------------------------------

	/**
	 * Resolves the Oracle home (the FMW product install root that holds {@code inventory/}).
	 * {@code platform.home} is set on every WebLogic JVM to {@code $MW_HOME/wlserver}, so its
	 * parent is the home; the FMW {@code oracle.home}/{@code bea.home} props and the usual env
	 * vars are tried first when present.
	 */
	private File oracleHome() {
		for (String p : new String[] {
				System.getProperty("oracle.home"),
				System.getProperty("bea.home"),
				System.getenv("ORACLE_HOME"),
				System.getenv("MW_HOME") }) {
			if (p != null && !p.isEmpty() && new File(p, "inventory").isDirectory()) {
				return new File(p);
			}
		}
		// platform.home -> $MW_HOME/wlserver ; wls.home -> $MW_HOME/wlserver/server
		String platformHome = System.getProperty("platform.home");
		if (platformHome != null && !platformHome.isEmpty()) {
			File home = new File(platformHome).getParentFile();
			if (home != null && new File(home, "inventory").isDirectory()) return home;
		}
		String wlsHome = System.getProperty("wls.home", System.getProperty("weblogic.home"));
		if (wlsHome != null && !wlsHome.isEmpty()) {
			File home = new File(wlsHome).getParentFile();
			home = home != null ? home.getParentFile() : null;
			if (home != null && new File(home, "inventory").isDirectory()) return home;
		}
		return null;
	}

	/** OCCAS / WebLogic / Coherence product versions from the install inventory. */
	private void readComps(File oracleHome, ObjectNode platform) {
		File comps = new File(oracleHome, "inventory/ContentsXML/comps.xml");
		if (!comps.isFile()) return;
		try {
			Document doc = parse(comps);
			Map<String, String> ver = new LinkedHashMap<>();
			NodeList list = doc.getElementsByTagName("COMP");
			for (int i = 0; i < list.getLength(); i++) {
				Element c = (Element) list.item(i);
				String name = c.getAttribute("NAME");
				if (!name.isEmpty() && !ver.containsKey(name)) {
					ver.put(name, c.getAttribute("VER"));
				}
			}
			platform.put("occas", ver.getOrDefault("oracle.occas.components.occas", ""));
			platform.put("weblogic", ver.getOrDefault("oracle.as.install.wls", ""));
			platform.put("coherence", ver.getOrDefault("oracle.coherence", ""));
		} catch (Exception ignore) {
		}
	}

	/** Applied one-off patches: one {@code <patch-def>} root per inventory/patches/*.xml. */
	private ArrayNode readPatches(File oracleHome) {
		ArrayNode arr = mapper.createArrayNode();
		File dir = new File(oracleHome, "inventory/patches");
		File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
		if (files == null) return arr;
		List<ObjectNode> rows = new ArrayList<>();
		for (File f : files) {
			try {
				Element root = parse(f).getDocumentElement();
				if (root == null) continue;
				String id = root.getAttribute("patch-id");
				if (id.isEmpty()) continue;
				ObjectNode n = mapper.createObjectNode();
				n.put("id", id);
				n.put("description", root.getAttribute("description"));
				rows.add(n);
			} catch (Exception ignore) {
			}
		}
		rows.sort((a, b) -> a.get("id").asText().compareTo(b.get("id").asText()));
		rows.forEach(arr::add);
		return arr;
	}

	// ---- small helpers -----------------------------------------------------

	private static Document parse(File f) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(false);
		DocumentBuilder db = dbf.newDocumentBuilder();
		return db.parse(f);
	}

	private static String vendorSuffix(String vendor) {
		return (vendor == null || vendor.isEmpty()) ? "" : " (" + vendor + ")";
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static String str(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return v != null ? v.toString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException {
			super();
		}
	}
}
