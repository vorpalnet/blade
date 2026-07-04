package org.vorpal.blade.framework.v3.source;

/// JMX interface for browsing an application's own bundled `.java` source.
///
/// Every module packages its source next to its classes (the parent pom's
/// `.java`-bundling resource puts app source in `WEB-INF/classes/` and
/// framework source inside the framework JAR), and [SourceRegistrar] registers
/// one of these per app as `vorpal.blade:Name=<app>,Type=Source[,Cluster=...]`.
/// The Callflow Viewer reads it over federated JMX — from a single designated
/// engine node, so browsing never touches the nodes carrying traffic — and the
/// source it shows is byte-identical to the code deployed in that WAR.
///
/// Registered via explicit `StandardMBean(..., SourceMXBean.class, true)` for
/// the same reason as the v2 `SettingsMXBean` — see memory
/// `[[settingsmxbean-introspection-bug]]`.
public interface SourceMXBean {

	/// JSON inventory of this app's browsable source:
	/// `{"app": "<name>", "sources": [{"className": "...", "callflow": true|false}, ...]}`.
	/// A `callflow` entry is a class assignable to the BLADE `Callflow` base —
	/// the classes the Callflow Viewer puts in its gallery.
	String getManifestJson();

	/// The raw `.java` text of one manifest-listed class, or `null` if the
	/// class isn't in the manifest (the manifest is the whitelist — no
	/// caller-supplied path ever reaches the classpath lookup).
	String getSource(String className);
}
